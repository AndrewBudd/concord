package com.walmartlabs.concord.plugins.boxcutter;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2025 Walmart Inc.
 * -----
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * =====
 */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.runtime.v2.sdk.TaskResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Serializable;
import java.util.*;
import java.util.Base64;

/**
 * Core logic for the boxcutter task plugin. Orchestrates ephemeral VMs
 * using the boxcutter CLI and communicates with them via the metadata
 * service messaging API.
 *
 * <p>Workflow:
 * <ol>
 *   <li>START: creates a VM, deploys a runner script, waits for readiness</li>
 *   <li>RUNSTEP: sends a task message to the VM, waits for result</li>
 *   <li>DESTROY: tears down the VM</li>
 * </ol>
 */
public class BoxcutterTaskCommon {

    private static final Logger log = LoggerFactory.getLogger(BoxcutterTaskCommon.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private static final long RUNNER_READY_TIMEOUT_MS = 120_000;
    private static final long POLL_INTERVAL_MS = 2_000;

    private final String orchestratorVmName;
    private final MetadataMessageClient messageClient;

    public BoxcutterTaskCommon(String orchestratorVmName, MetadataMessageClient messageClient) {
        this.orchestratorVmName = orchestratorVmName;
        this.messageClient = messageClient;
    }

    public TaskResult execute(BoxcutterParams params) throws Exception {
        return switch (params.action()) {
            case START -> start(params);
            case RUNSTEP -> runStep(params);
            case DESTROY -> destroy(params);
        };
    }

    private TaskResult start(BoxcutterParams params) throws Exception {
        String vmType = params.vmType();
        String sessionId = UUID.randomUUID().toString();

        // Build the boxcutter new command
        List<String> newCmd = new ArrayList<>();
        newCmd.add("new");
        newCmd.add("--type");
        newCmd.add(vmType);
        newCmd.add("--vcpu");
        newCmd.add(String.valueOf(params.vcpu()));
        newCmd.add("--ram");
        newCmd.add(String.valueOf(params.ram()));
        newCmd.add("--disk");
        newCmd.add(params.disk());
        newCmd.add("--desc");
        newCmd.add("concord-session-" + sessionId);

        String vmName = params.vmName();
        if (vmName != null) {
            newCmd.add("--name");
            newCmd.add(vmName);
        }

        log.info("Creating boxcutter VM (type={}, session={})...", vmType, sessionId);
        BoxcutterCommand.Result result = BoxcutterCommand.exec(newCmd.toArray(new String[0]));
        result.assertSuccess("Failed to create boxcutter VM");

        // Parse the VM name from output
        String createdVmName = parseVmName(result.output(), vmName);
        log.info("Created boxcutter VM: {}", createdVmName);

        // Deploy the runner script to the VM via sendkeys
        deployRunner(createdVmName, sessionId);

        // Wait for the runner to signal it's ready
        log.info("Waiting for runner on VM {} to become ready...", createdVmName);
        waitForRunnerReady(createdVmName, sessionId);

        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", sessionId);
        session.put("vmName", createdVmName);
        session.put("orchestratorVm", orchestratorVmName);
        session.put("state", new HashMap<String, Object>());

        return TaskResult.success()
                .value("session", asSerializable(session))
                .value("vmName", createdVmName)
                .value("sessionId", sessionId);
    }

    private TaskResult runStep(BoxcutterParams params) throws Exception {
        Map<String, Object> session = params.session();
        if (session.isEmpty()) {
            throw new IllegalArgumentException("'session' is required for runStep action");
        }

        String vmName = (String) session.get("vmName");
        String sessionId = (String) session.get("sessionId");
        if (sessionId == null || vmName == null) {
            throw new IllegalArgumentException("session.sessionId and session.vmName are required. Did you call 'start' first?");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sessionState = (Map<String, Object>) session.getOrDefault("state", Collections.emptyMap());

        // Build the task message
        Map<String, Object> taskMessage = new HashMap<>();
        String taskId = UUID.randomUUID().toString();
        taskMessage.put("taskId", taskId);
        taskMessage.put("sessionId", sessionId);
        taskMessage.put("replyTo", orchestratorVmName);

        String entryPoint = params.entryPoint();
        String command = params.command();

        if (command != null) {
            taskMessage.put("type", "command");
            taskMessage.put("command", command);
        } else if (entryPoint != null) {
            taskMessage.put("type", "entryPoint");
            taskMessage.put("entryPoint", entryPoint);
        } else {
            throw new IllegalArgumentException("Either 'entryPoint' or 'command' is required for runStep");
        }

        // Merge session state with step arguments
        Map<String, Object> arguments = new HashMap<>(sessionState);
        arguments.putAll(params.arguments());
        taskMessage.put("arguments", arguments);

        Collection<String> outVars = params.outVars();
        if (!outVars.isEmpty()) {
            taskMessage.put("outVars", new ArrayList<>(outVars));
        }

        String messageBody = objectMapper.writeValueAsString(taskMessage);

        log.info("Sending task to VM {} (taskId={}, type={})...", vmName, taskId,
                command != null ? "command" : "entryPoint:" + entryPoint);
        messageClient.send(vmName, "concord-task", messageBody);

        // Poll for the response
        long timeout = params.timeout();
        Map<String, Object> responseData = waitForTaskResponse(taskId, sessionId, timeout);

        // Check for errors
        String error = (String) responseData.get("error");
        if (error != null) {
            return TaskResult.fail("VM task failed: " + error);
        }

        // Extract output variables
        @SuppressWarnings("unchecked")
        Map<String, Object> outputVars = (Map<String, Object>) responseData.getOrDefault("output", Collections.emptyMap());

        // Merge output into session state
        Map<String, Object> newState = new HashMap<>(sessionState);
        newState.putAll(outputVars);

        Map<String, Object> updatedSession = new HashMap<>(session);
        updatedSession.put("state", newState);

        TaskResult.SimpleResult result = TaskResult.success()
                .value("session", asSerializable(updatedSession));

        // Also set output vars as top-level result values
        for (Map.Entry<String, Object> entry : outputVars.entrySet()) {
            Object v = entry.getValue();
            if (v instanceof Serializable) {
                result.value(entry.getKey(), v);
            } else if (v != null) {
                result.value(entry.getKey(), v.toString());
            }
        }

        // Include stdout/stderr if present
        String stdout = (String) responseData.get("stdout");
        String stderr = (String) responseData.get("stderr");
        if (stdout != null) {
            result.value("stdout", stdout);
        }
        if (stderr != null) {
            result.value("stderr", stderr);
        }

        Integer exitCode = (Integer) responseData.get("exitCode");
        if (exitCode != null) {
            result.value("exitCode", exitCode);
        }

        return result;
    }

    private TaskResult destroy(BoxcutterParams params) throws Exception {
        Map<String, Object> session = params.session();
        String vmName;

        if (!session.isEmpty()) {
            vmName = (String) session.get("vmName");
        } else {
            vmName = params.vmName();
        }

        if (vmName == null) {
            throw new IllegalArgumentException("VM name is required for destroy action");
        }

        log.info("Destroying boxcutter VM: {}", vmName);

        // Send shutdown message to the runner (best-effort)
        try {
            Map<String, Object> shutdownMsg = Map.of(
                    "type", "shutdown",
                    "taskId", UUID.randomUUID().toString()
            );
            messageClient.send(vmName, "concord-task", objectMapper.writeValueAsString(shutdownMsg));
        } catch (Exception e) {
            log.debug("Failed to send shutdown message (VM may already be gone): {}", e.getMessage());
        }

        BoxcutterCommand.Result result = BoxcutterCommand.exec("destroy", vmName);

        if (result.exitCode() != 0) {
            log.warn("Failed to destroy VM {} (exit code {}): {}", vmName, result.exitCode(), result.output());
        } else {
            log.info("Destroyed boxcutter VM: {}", vmName);
        }

        return TaskResult.success()
                .value("destroyed", result.exitCode() == 0);
    }

    private void deployRunner(String vmName, String sessionId) throws Exception {
        // Deploy the runner script to the VM using boxcutter exec + base64.
        // Base64 encoding avoids heredoc/quoting issues when passing through exec.
        // The runner is a bash script that:
        // 1. Polls the metadata service for task messages
        // 2. Executes commands or scripts
        // 3. Sends results back to the orchestrator VM

        String runnerScript = buildRunnerScript(sessionId, orchestratorVmName);
        // Strip leading whitespace from each line (Java text block indentation)
        String cleanedScript = runnerScript.lines()
                .map(String::stripLeading)
                .collect(java.util.stream.Collectors.joining("\n"));
        String base64Script = Base64.getEncoder().encodeToString(cleanedScript.getBytes());

        String deployCmd = "echo " + base64Script + " | base64 -d > /tmp/concord-runner.sh"
                + " && chmod +x /tmp/concord-runner.sh"
                + " && nohup /tmp/concord-runner.sh > /tmp/concord-runner.log 2>&1 &";

        BoxcutterCommand.exec("exec", vmName, deployCmd);
        log.info("Deployed runner script to VM {}", vmName);
    }

    static String buildRunnerScript(String sessionId, String orchestratorVm) {
        // Generate a bash runner script that runs on the VM.
        // It polls the metadata service inbox for concord-task messages,
        // executes them, and sends results back.
        return """
                #!/bin/bash
                set -euo pipefail

                METADATA_URL="http://169.254.169.254"
                SESSION_ID="%s"
                REPLY_TO="%s"
                WORK_DIR="/tmp/concord-work"

                mkdir -p "$WORK_DIR"

                log() {
                    echo "[concord-runner $(date -u +%%Y-%%m-%%dT%%H:%%M:%%SZ)] $*"
                }

                send_message() {
                    local to="$1"
                    local subject="$2"
                    local body="$3"
                    curl -sf -X POST "$METADATA_URL/messages/send" \\
                        -H "Content-Type: application/json" \\
                        -d "$(printf '{"to":"%%s","subject":"%%s","body":"%%s"}' "$to" "$subject" "$(echo "$body" | sed 's/\\\\/\\\\\\\\/g; s/"/\\\\"/g; s/\\t/\\\\t/g; s/\\r/\\\\r/g')")" \\
                        > /dev/null 2>&1
                }

                send_json_message() {
                    local to="$1"
                    local subject="$2"
                    local body_file="$3"
                    local payload
                    payload=$(jq -n --arg to "$to" --arg subject "$subject" --rawfile body "$body_file" \\
                        '{to: $to, subject: $subject, body: $body}')
                    curl -sf -X POST "$METADATA_URL/messages/send" \\
                        -H "Content-Type: application/json" \\
                        -d "$payload" > /dev/null 2>&1
                }

                ack_message() {
                    curl -sf -X DELETE "$METADATA_URL/messages/$1" > /dev/null 2>&1 || true
                }

                # Signal readiness
                log "Runner starting (session=$SESSION_ID)"
                READY_BODY=$(printf '{"type":"ready","sessionId":"%%s"}' "$SESSION_ID")
                send_message "$REPLY_TO" "concord-response" "$READY_BODY"
                log "Sent ready signal to $REPLY_TO"

                # Main message processing loop
                while true; do
                    MSGS=$(curl -sf "$METADATA_URL/messages" 2>/dev/null || echo "[]")

                    if [ "$MSGS" = "[]" ] || [ -z "$MSGS" ]; then
                        sleep 2
                        continue
                    fi

                    # Process the first message only (avoid subshell issues with piped while-read)
                    msg=$(echo "$MSGS" | jq -c '.[0]' 2>/dev/null)
                    if [ -z "$msg" ] || [ "$msg" = "null" ]; then sleep 2; continue; fi

                    MSG_ID=$(echo "$msg" | jq -r '.id')
                    SUBJECT=$(echo "$msg" | jq -r '.subject')
                    BODY=$(echo "$msg" | jq -r '.body')

                        # Only process concord-task messages
                        if [ "$SUBJECT" != "concord-task" ]; then
                            ack_message "$MSG_ID"
                            continue
                        fi

                        TASK_ID=$(echo "$BODY" | jq -r '.taskId // empty')
                        MSG_TYPE=$(echo "$BODY" | jq -r '.type // empty')

                        if [ -z "$TASK_ID" ]; then
                            ack_message "$MSG_ID"
                            continue
                        fi

                        log "Processing task $TASK_ID (type=$MSG_TYPE)"
                        ack_message "$MSG_ID"

                        # Clear any CONCORD_OUT_ vars from previous tasks
                        for v in $(env | grep '^CONCORD_OUT_' | cut -d= -f1); do unset "$v"; done

                        # Handle shutdown
                        if [ "$MSG_TYPE" = "shutdown" ]; then
                            log "Shutdown requested, exiting"
                            exit 0
                        fi

                        # Process the task
                        RESULT_FILE=$(mktemp)

                        if [ "$MSG_TYPE" = "command" ]; then
                            CMD=$(echo "$BODY" | jq -r '.command')
                            ARGS_JSON=$(echo "$BODY" | jq -r '.arguments // {}')

                            # Export arguments as environment variables
                            ARGS_ENV_FILE=$(mktemp)
                            echo "$ARGS_JSON" | jq -r 'to_entries[] | "CONCORD_ARG_\\(.key)=\\(.value | tostring)"' > "$ARGS_ENV_FILE" 2>/dev/null || true
                            while IFS='=' read -r key val; do export "$key=$val"; done < "$ARGS_ENV_FILE"
                            rm -f "$ARGS_ENV_FILE"

                            # Run the command in a wrapper that captures CONCORD_OUT_ vars
                            STDOUT_FILE=$(mktemp)
                            STDERR_FILE=$(mktemp)
                            ENV_FILE=$(mktemp)
                            WRAPPER_FILE=$(mktemp)
                            EXIT_CODE=0
                            cd "$WORK_DIR"

                            # Write a wrapper script that runs the command and dumps CONCORD_OUT_ vars
                            cat > "$WRAPPER_FILE" << 'WRAPPER_EOF'
#!/bin/bash
set +e
eval "$CONCORD_CMD"
CMD_EXIT=$?
# Dump CONCORD_OUT_ vars to the env file
env | grep '^CONCORD_OUT_' > "$CONCORD_ENV_FILE" 2>/dev/null || true
exit $CMD_EXIT
WRAPPER_EOF
                            chmod +x "$WRAPPER_FILE"

                            CONCORD_CMD="$CMD" CONCORD_ENV_FILE="$ENV_FILE" \\
                                bash "$WRAPPER_FILE" > "$STDOUT_FILE" 2> "$STDERR_FILE" || EXIT_CODE=$?

                            STDOUT_CONTENT=$(cat "$STDOUT_FILE" | head -c 65536)
                            STDERR_CONTENT=$(cat "$STDERR_FILE" | head -c 65536)

                            # Read output vars from the env dump file
                            OUT_VARS=$(echo "$BODY" | jq -r '.outVars // []')
                            OUTPUT_JSON="{}"
                            if [ "$OUT_VARS" != "[]" ] && [ -f "$ENV_FILE" ]; then
                                for var in $(echo "$OUT_VARS" | jq -r '.[]'); do
                                    VAR_VAL=$(grep "^CONCORD_OUT_${var}=" "$ENV_FILE" | head -1 | cut -d= -f2-)
                                    if [ -n "$VAR_VAL" ]; then
                                        OUTPUT_JSON=$(echo "$OUTPUT_JSON" | jq --arg k "$var" --arg v "$VAR_VAL" '. + {($k): $v}')
                                    fi
                                done
                            fi
                            rm -f "$WRAPPER_FILE" "$ENV_FILE"

                            jq -n \\
                                --arg taskId "$TASK_ID" \\
                                --arg sessionId "$SESSION_ID" \\
                                --arg stdout "$STDOUT_CONTENT" \\
                                --arg stderr "$STDERR_CONTENT" \\
                                --argjson exitCode "$EXIT_CODE" \\
                                --argjson output "$OUTPUT_JSON" \\
                                '{type:"result", taskId:$taskId, sessionId:$sessionId, stdout:$stdout, stderr:$stderr, exitCode:$exitCode, output:$output}' \\
                                > "$RESULT_FILE"

                            rm -f "$STDOUT_FILE" "$STDERR_FILE"

                        elif [ "$MSG_TYPE" = "entryPoint" ]; then
                            ENTRY_POINT=$(echo "$BODY" | jq -r '.entryPoint')
                            ARGS_JSON=$(echo "$BODY" | jq -c '.arguments // {}')
                            OUT_VARS=$(echo "$BODY" | jq -c '.outVars // []')

                            # For entryPoint tasks, we create a script from the arguments
                            # and the entry point name, then execute it
                            SCRIPT_FILE="$WORK_DIR/entrypoint-${ENTRY_POINT}.sh"

                            if [ -f "$SCRIPT_FILE" ]; then
                                # Execute existing entry point script
                                chmod +x "$SCRIPT_FILE"

                                STDOUT_FILE=$(mktemp)
                                STDERR_FILE=$(mktemp)
                                EXIT_CODE=0

                                # Pass arguments as JSON via env
                                export CONCORD_ARGS="$ARGS_JSON"
                                export CONCORD_ENTRY_POINT="$ENTRY_POINT"
                                export CONCORD_OUT_VARS="$OUT_VARS"
                                export CONCORD_WORK_DIR="$WORK_DIR"

                                cd "$WORK_DIR"
                                bash "$SCRIPT_FILE" > "$STDOUT_FILE" 2> "$STDERR_FILE" || EXIT_CODE=$?

                                STDOUT_CONTENT=$(cat "$STDOUT_FILE" | head -c 65536)
                                STDERR_CONTENT=$(cat "$STDERR_FILE" | head -c 65536)

                                # Read output vars from the output file if it exists
                                OUTPUT_JSON="{}"
                                if [ -f "$WORK_DIR/.concord-output.json" ]; then
                                    OUTPUT_JSON=$(cat "$WORK_DIR/.concord-output.json")
                                    rm -f "$WORK_DIR/.concord-output.json"
                                fi

                                jq -n \\
                                    --arg taskId "$TASK_ID" \\
                                    --arg sessionId "$SESSION_ID" \\
                                    --arg stdout "$STDOUT_CONTENT" \\
                                    --arg stderr "$STDERR_CONTENT" \\
                                    --argjson exitCode "$EXIT_CODE" \\
                                    --argjson output "$OUTPUT_JSON" \\
                                    '{type:"result", taskId:$taskId, sessionId:$sessionId, stdout:$stdout, stderr:$stderr, exitCode:$exitCode, output:$output}' \\
                                    > "$RESULT_FILE"

                                rm -f "$STDOUT_FILE" "$STDERR_FILE"
                            else
                                # No script found - report error
                                jq -n \\
                                    --arg taskId "$TASK_ID" \\
                                    --arg sessionId "$SESSION_ID" \\
                                    --arg error "Entry point script not found: $SCRIPT_FILE" \\
                                    '{type:"result", taskId:$taskId, sessionId:$sessionId, error:$error}' \\
                                    > "$RESULT_FILE"
                            fi
                        else
                            jq -n \\
                                --arg taskId "$TASK_ID" \\
                                --arg sessionId "$SESSION_ID" \\
                                --arg error "Unknown task type: $MSG_TYPE" \\
                                '{type:"result", taskId:$taskId, sessionId:$sessionId, error:$error}' \\
                                > "$RESULT_FILE"
                        fi

                        # Send the result back
                        log "Sending result for task $TASK_ID"
                        send_json_message "$REPLY_TO" "concord-response" "$RESULT_FILE"
                        rm -f "$RESULT_FILE"

                done
                """.formatted(sessionId, orchestratorVm);
    }

    private void waitForRunnerReady(String vmName, String sessionId) throws Exception {
        long deadline = System.currentTimeMillis() + RUNNER_READY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            List<MetadataMessageClient.Message> messages = messageClient.readMessages();

            for (MetadataMessageClient.Message msg : messages) {
                if (!"concord-response".equals(msg.subject())) {
                    // Not for us, but ack to clear it (it came back in-flight)
                    continue;
                }

                try {
                    Map<String, Object> body = objectMapper.readValue(msg.body(), new TypeReference<>() {});
                    String type = (String) body.get("type");
                    String msgSessionId = (String) body.get("sessionId");

                    if ("ready".equals(type) && sessionId.equals(msgSessionId)) {
                        messageClient.acknowledge(msg.id());
                        log.info("Runner on VM {} is ready (session={})", vmName, sessionId);
                        return;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse ready message: {}", e.getMessage());
                }
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("Timeout waiting for runner on VM " + vmName +
                " to become ready (waited " + RUNNER_READY_TIMEOUT_MS + "ms)");
    }

    private Map<String, Object> waitForTaskResponse(String taskId, String sessionId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;

        while (System.currentTimeMillis() < deadline) {
            List<MetadataMessageClient.Message> messages = messageClient.readMessages();

            for (MetadataMessageClient.Message msg : messages) {
                if (!"concord-response".equals(msg.subject())) {
                    continue;
                }

                try {
                    Map<String, Object> body = objectMapper.readValue(msg.body(), new TypeReference<>() {});
                    String type = (String) body.get("type");
                    String respTaskId = (String) body.get("taskId");
                    String respSessionId = (String) body.get("sessionId");

                    if ("result".equals(type) && taskId.equals(respTaskId) && sessionId.equals(respSessionId)) {
                        messageClient.acknowledge(msg.id());
                        log.info("Received result for task {}", taskId);
                        return body;
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse response message: {}", e.getMessage());
                }
            }

            Thread.sleep(POLL_INTERVAL_MS);
        }

        throw new RuntimeException("Timeout waiting for task " + taskId +
                " response (waited " + timeoutMs + "ms)");
    }

    private String parseVmName(String output, String requestedName) {
        if (requestedName != null) {
            return requestedName;
        }

        // boxcutter 'new' output includes "Name:    <vm-name>"
        // Parse that specific line to extract the VM name
        String trimmed = output.trim();
        if (!trimmed.isEmpty()) {
            String[] lines = trimmed.split("\n");
            for (String line : lines) {
                String stripped = line.trim();
                if (stripped.startsWith("Name:")) {
                    String name = stripped.substring(5).trim();
                    if (!name.isEmpty()) {
                        return name;
                    }
                }
            }
        }

        return "concord-vm-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("unchecked")
    static HashMap<String, Serializable> asSerializable(Map<String, Object> map) {
        HashMap<String, Serializable> result = new HashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Map) {
                result.put(entry.getKey(), asSerializable((Map<String, Object>) value));
            } else if (value instanceof Serializable) {
                result.put(entry.getKey(), (Serializable) value);
            } else if (value != null) {
                result.put(entry.getKey(), value.toString());
            }
        }
        return result;
    }
}
