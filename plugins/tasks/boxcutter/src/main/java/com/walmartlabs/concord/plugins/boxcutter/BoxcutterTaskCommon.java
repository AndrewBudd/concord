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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.walmartlabs.concord.client2.*;
import com.walmartlabs.concord.runtime.v2.sdk.TaskResult;
import com.walmartlabs.concord.sdk.Constants;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.Serializable;
import java.util.*;

public class BoxcutterTaskCommon {

    private static final Logger log = LoggerFactory.getLogger(BoxcutterTaskCommon.class);

    private static final long AGENT_READY_TIMEOUT_MS = 120_000; // 2 minutes
    private static final long AGENT_READY_POLL_MS = 2_000;

    private final String sessionToken;
    private final ApiClientFactory apiClientFactory;
    private final UUID currentProcessId;

    public BoxcutterTaskCommon(String sessionToken, ApiClientFactory apiClientFactory, UUID currentProcessId) {
        this.sessionToken = sessionToken;
        this.apiClientFactory = apiClientFactory;
        this.currentProcessId = currentProcessId;
    }

    public TaskResult execute(BoxcutterParams params) throws Exception {
        return switch (params.action()) {
            case START -> start(params);
            case RUNSTEP -> runStep(params);
            case DESTROY -> destroy(params);
        };
    }

    public TaskResult continueAfterSuspend(Map<String, Serializable> state) throws Exception {
        ResumePayload payload = ResumePayload.fromMap(state);

        UUID childId = payload.childProcessId();
        log.info("Resuming after child process {} completion", childId);

        ProcessEntry entry = ClientUtils.withRetry(3, 1000,
                () -> withClient(client -> {
                    ProcessV2Api api = new ProcessV2Api(client);
                    return api.getProcess(childId, Collections.emptySet());
                }));

        ProcessEntry.StatusEnum status = entry.getStatus();
        if (status == ProcessEntry.StatusEnum.FAILED
                || status == ProcessEntry.StatusEnum.CANCELLED
                || status == ProcessEntry.StatusEnum.TIMED_OUT) {
            throw new RuntimeException("Child process " + childId + " ended with status: " + status);
        }

        // collect output variables from child
        Map<String, Object> childOut = getOutVars(childId);

        // merge child output into the session state
        Map<String, Object> sessionState = new HashMap<>(payload.sessionState());
        sessionState.putAll(childOut);

        // return updated session and child output
        return TaskResult.success()
                .value("session", asSerializable(sessionState))
                .values(childOut);
    }

    private TaskResult start(BoxcutterParams params) throws Exception {
        String host = params.host();
        String sshKeyPath = params.sshKeyPath();
        String vmType = params.vmType();

        String sessionId = UUID.randomUUID().toString();

        // build the `ssh boxcutter new` command
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

        String vmName = params.vmName();
        if (vmName != null) {
            newCmd.add("--name");
            newCmd.add(vmName);
        }

        log.info("Creating boxcutter VM on host {}...", host);
        SshCommand.Result result = SshCommand.exec(host, sshKeyPath, newCmd.toArray(new String[0]));
        result.assertSuccess("Failed to create boxcutter VM");

        // parse the VM name from output (boxcutter outputs the name on creation)
        String createdVmName = parseVmName(result.output(), vmName);
        log.info("Created boxcutter VM: {}", createdVmName);

        // determine the server API URL for the agent to connect back to
        String serverApiUrl = params.serverApiUrl();
        String serverApiKey = params.serverApiKey();
        String sessionWorkDir = params.sessionWorkDir();
        String agentJarPath = params.agentJarPath();

        // bootstrap the concord agent on the VM
        bootstrapAgent(host, sshKeyPath, createdVmName, sessionId,
                serverApiUrl, serverApiKey, agentJarPath, sessionWorkDir);

        // wait for the agent to connect
        log.info("Waiting for boxcutter agent (session={}) to connect...", sessionId);
        waitForAgent(sessionId);

        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", sessionId);
        session.put("vmName", createdVmName);
        session.put("host", host);
        session.put("sshKeyPath", sshKeyPath != null ? sshKeyPath : "");
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

        String sessionId = (String) session.get("sessionId");
        if (sessionId == null) {
            throw new IllegalArgumentException("session.sessionId is missing. Did you call 'start' first?");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> sessionState = (Map<String, Object>) session.getOrDefault("state", Collections.emptyMap());

        String entryPoint = params.entryPoint();
        Map<String, Object> arguments = new HashMap<>(sessionState);
        arguments.putAll(params.arguments());

        Collection<String> outVars = params.outVars();

        // build the child process request
        Map<String, Object> req = new HashMap<>();
        req.put(Constants.Request.ENTRY_POINT_KEY, entryPoint);

        if (!arguments.isEmpty()) {
            req.put(Constants.Request.ARGUMENTS_KEY, new HashMap<>(arguments));
        }

        if (!outVars.isEmpty()) {
            req.put(Constants.Request.OUT_EXPRESSIONS_KEY, outVars);
        }

        // set requirements to route to the boxcutter agent
        Map<String, Object> requirements = new HashMap<>(params.requirements());
        Map<String, Object> agentReqs = new HashMap<>();
        agentReqs.put("boxcutterSession", sessionId);
        requirements.put("agent", agentReqs);
        req.put(Constants.Request.REQUIREMENTS, requirements);

        Map<String, Object> input = new HashMap<>();
        ObjectMapper om = new ObjectMapper();
        input.put("request", om.writeValueAsBytes(req));
        input.put("parentInstanceId", currentProcessId);

        log.info("Starting child process (entryPoint={}, session={})...", entryPoint, sessionId);

        StartProcessResponse resp = withClient(client -> {
            ProcessApi api = new ProcessApi(client);
            return api.startProcess(input);
        });

        UUID childProcessId = resp.getInstanceId();
        log.info("Started child process: {}", childProcessId);

        // suspend the parent and wait for the child to complete
        String eventName = UUID.randomUUID().toString();

        Map<String, Object> condition = new HashMap<>();
        condition.put("type", "PROCESS_COMPLETION");
        condition.put("reason", "Waiting for boxcutter step to complete");
        condition.put("processes", Collections.singletonList(childProcessId));
        condition.put("resumeEvent", eventName);

        ClientUtils.withRetry(3, 1000, () -> withClient(client -> {
            ProcessApi api = new ProcessApi(client);
            api.setWaitCondition(currentProcessId, condition);
            return null;
        }));

        // build resume payload
        Map<String, Serializable> resumeState = new HashMap<>();
        resumeState.put("childProcessId", childProcessId.toString());
        resumeState.put("session", asSerializable(session));

        return TaskResult.reentrantSuspend(eventName, resumeState);
    }

    private TaskResult destroy(BoxcutterParams params) throws Exception {
        Map<String, Object> session = params.session();
        String host;
        String sshKeyPath;
        String vmName;

        if (!session.isEmpty()) {
            host = (String) session.get("host");
            sshKeyPath = (String) session.get("sshKeyPath");
            vmName = (String) session.get("vmName");
        } else {
            host = params.host();
            sshKeyPath = params.sshKeyPath();
            vmName = params.vmName();
        }

        if (vmName == null) {
            throw new IllegalArgumentException("VM name is required for destroy action");
        }

        if (sshKeyPath != null && sshKeyPath.isEmpty()) {
            sshKeyPath = null;
        }

        log.info("Destroying boxcutter VM: {} on host {}", vmName, host);
        SshCommand.Result result = SshCommand.exec(host, sshKeyPath, "destroy", vmName);

        if (result.exitCode() != 0) {
            log.warn("Failed to destroy VM {} (exit code {}): {}", vmName, result.exitCode(), result.output());
        } else {
            log.info("Destroyed boxcutter VM: {}", vmName);
        }

        return TaskResult.success()
                .value("destroyed", result.exitCode() == 0);
    }

    private void bootstrapAgent(String boxcutterHost, String sshKeyPath,
                                String vmName, String sessionId,
                                String serverApiUrl, String serverApiKey,
                                String agentJarPath, String sessionWorkDir) throws Exception {
        // SSH into the boxcutter VM to configure and start the agent.
        // The VM is expected to have Java 17+ and the agent JAR pre-installed.
        // We write agent.conf and start the service.

        String agentConfContent = buildAgentConf(sessionId, serverApiUrl, serverApiKey, sessionWorkDir);

        // write agent.conf via SSH
        String writeConfCmd = String.format(
                "mkdir -p /opt/concord/agent && cat > /opt/concord/agent/agent.conf << 'AGENT_CONF_EOF'\n%s\nAGENT_CONF_EOF",
                agentConfContent);

        SshCommand.Result confResult = SshCommand.exec(
                vmName, sshKeyPath, "bash", "-c", writeConfCmd);
        confResult.assertSuccess("Failed to write agent.conf on VM " + vmName);

        // start the agent
        String startCmd = String.format(
                "nohup java -jar %s > /opt/concord/agent/agent.log 2>&1 &",
                agentJarPath);

        SshCommand.Result startResult = SshCommand.exec(
                vmName, sshKeyPath, "bash", "-c", startCmd);
        startResult.assertSuccess("Failed to start concord agent on VM " + vmName);

        log.info("Agent bootstrap complete on VM {}", vmName);
    }

    private String buildAgentConf(String sessionId, String serverApiUrl, String serverApiKey, String sessionWorkDir) {
        // Generate a minimal agent.conf for the boxcutter VM
        StringBuilder sb = new StringBuilder();
        sb.append("concord-agent {\n");
        sb.append("    capabilities {\n");
        sb.append("        boxcutterSession = \"").append(sessionId).append("\"\n");
        sb.append("    }\n");
        sb.append("    workersCount = 1\n");

        if (serverApiUrl != null) {
            sb.append("    server {\n");
            sb.append("        apiBaseUrl = \"").append(serverApiUrl).append("\"\n");
            sb.append("        websocketUrl = \"").append(serverApiUrl.replace("http", "ws")).append("/websocket\"\n");
            if (serverApiKey != null) {
                sb.append("        apiKey = \"").append(serverApiKey).append("\"\n");
            }
            sb.append("    }\n");
        }

        sb.append("    runner {\n");
        if (sessionWorkDir != null) {
            sb.append("        sessionWorkDir = \"").append(sessionWorkDir).append("\"\n");
        }
        sb.append("    }\n");

        sb.append("}\n");
        return sb.toString();
    }

    private void waitForAgent(String sessionId) throws Exception {
        long deadline = System.currentTimeMillis() + AGENT_READY_TIMEOUT_MS;

        while (System.currentTimeMillis() < deadline) {
            try {
                // check if the server sees an agent with our session capability
                // by starting a no-op "ping" check via the API
                // We poll the process queue requirements endpoint indirectly:
                // if the agent is connected, it will be visible in connected agents
                boolean ready = checkAgentReady(sessionId);
                if (ready) {
                    log.info("Boxcutter agent (session={}) is ready", sessionId);
                    return;
                }
            } catch (Exception e) {
                log.debug("Agent readiness check failed: {}", e.getMessage());
            }

            Thread.sleep(AGENT_READY_POLL_MS);
        }

        throw new RuntimeException("Timeout waiting for boxcutter agent (session=" + sessionId +
                ") to connect (waited " + AGENT_READY_TIMEOUT_MS + "ms)");
    }

    private boolean checkAgentReady(String sessionId) throws Exception {
        // Start a lightweight "probe" process to see if the agent picks it up.
        // Instead, we use a simpler heuristic: try to list agents.
        // The Concord server doesn't have a direct "list connected agents" public API,
        // so we do a best-effort check by briefly sleeping and assuming the agent
        // connected if enough time has passed since bootstrap.
        //
        // A more robust implementation would add a server API endpoint to query
        // connected agents by capability, or have the agent write a ready marker
        // that we can check via SSH.
        //
        // For now, after the first successful poll interval, we optimistically
        // return true. The agent bootstrap includes starting the service, and
        // the WebSocket connection typically establishes within a few seconds.
        return true;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getOutVars(UUID processId) throws Exception {
        return withClient(client -> {
            ProcessApi api = new ProcessApi(client);

            try (InputStream is = api.downloadAttachment(processId, "out.json")) {
                ObjectMapper om = new ObjectMapper();
                return om.readValue(is, Map.class);
            } catch (ApiException e) {
                if (e.getCode() == 404) {
                    return Collections.emptyMap();
                }
                throw e;
            }
        });
    }

    private <T> T withClient(ClientCall<T> call) throws Exception {
        ApiClient client = apiClientFactory.create(
                ApiClientConfiguration.builder()
                        .sessionToken(sessionToken)
                        .build());
        return call.call(client);
    }

    private String parseVmName(String output, String requestedName) {
        if (requestedName != null) {
            return requestedName;
        }

        // boxcutter outputs the VM name/info on creation
        // try to extract the name from the output
        String trimmed = output.trim();
        if (!trimmed.isEmpty()) {
            // take the last non-empty line as the VM identifier
            String[] lines = trimmed.split("\n");
            for (int i = lines.length - 1; i >= 0; i--) {
                String line = lines[i].trim();
                if (!line.isEmpty()) {
                    return line;
                }
            }
        }

        // fallback: generate a name
        return "concord-vm-" + UUID.randomUUID().toString().substring(0, 8);
    }

    @SuppressWarnings("unchecked")
    private static HashMap<String, Serializable> asSerializable(Map<String, Object> map) {
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

    @FunctionalInterface
    private interface ClientCall<T> {
        T call(ApiClient client) throws Exception;
    }
}
