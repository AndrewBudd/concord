package com.walmartlabs.concord.plugins.boxcutter;

/*-
 * *****
 * Concord
 * -----
 * Copyright (C) 2017 - 2026 Walmart Inc.
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

import com.walmartlabs.concord.runtime.v2.sdk.MapBackedVariables;
import com.walmartlabs.concord.runtime.v2.sdk.TaskResult;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BoxcutterTaskCommonTest {

    @Test
    void testRunnerScriptGeneration() {
        String script = BoxcutterTaskCommon.buildRunnerScript("session-123", "orchestrator-vm");

        assertNotNull(script);
        assertTrue(script.contains("SESSION_ID=\"session-123\""));
        assertTrue(script.contains("REPLY_TO=\"orchestrator-vm\""));
        assertTrue(script.contains("METADATA_URL=\"http://169.254.169.254\""));
        assertTrue(script.contains("concord-runner"));
        assertTrue(script.contains("concord-task"));
        assertTrue(script.contains("concord-response"));
        assertTrue(script.contains("send_message"));
        assertTrue(script.contains("ack_message"));
        // Verify it handles all task types
        assertTrue(script.contains("command"));
        assertTrue(script.contains("entryPoint"));
        assertTrue(script.contains("shutdown"));
    }

    @Test
    void testRunnerScriptContainsReadySignal() {
        String script = BoxcutterTaskCommon.buildRunnerScript("s1", "orch");
        assertTrue(script.contains("\"type\":\"ready\""));
        assertTrue(script.contains("\"sessionId\""));
    }

    @Test
    void testRunnerScriptHandlesOutputVars() {
        String script = BoxcutterTaskCommon.buildRunnerScript("s1", "orch");
        assertTrue(script.contains("CONCORD_OUT_"));
        assertTrue(script.contains("outVars"));
        assertTrue(script.contains(".concord-output.json"));
    }

    @Test
    void testAsSerializable() {
        Map<String, Object> input = new HashMap<>();
        input.put("string", "value");
        input.put("number", 42);
        input.put("bool", true);
        Map<String, Object> nested = new HashMap<>();
        nested.put("inner", "data");
        input.put("nested", nested);

        HashMap<String, Serializable> result = BoxcutterTaskCommon.asSerializable(input);

        assertEquals("value", result.get("string"));
        assertEquals(42, result.get("number"));
        assertEquals(true, result.get("bool"));
        assertInstanceOf(HashMap.class, result.get("nested"));
        @SuppressWarnings("unchecked")
        Map<String, Serializable> nestedResult = (Map<String, Serializable>) result.get("nested");
        assertEquals("data", nestedResult.get("inner"));
    }

    @Test
    void testAsSerializableWithNulls() {
        Map<String, Object> input = new HashMap<>();
        input.put("key", null);
        input.put("present", "value");

        HashMap<String, Serializable> result = BoxcutterTaskCommon.asSerializable(input);
        assertFalse(result.containsKey("key"));
        assertEquals("value", result.get("present"));
    }

    @Test
    void testRunStepRequiresSession() {
        BoxcutterTaskCommon common = new BoxcutterTaskCommon("orch", new MetadataMessageClient("http://localhost:1"));

        BoxcutterParams params = new BoxcutterParams(new MapBackedVariables(Map.of(
                "action", "runStep"
        )));

        assertThrows(IllegalArgumentException.class, () -> common.execute(params));
    }

    @Test
    void testRunStepRequiresSessionId() {
        BoxcutterTaskCommon common = new BoxcutterTaskCommon("orch", new MetadataMessageClient("http://localhost:1"));

        Map<String, Object> session = new HashMap<>();
        session.put("vmName", "test-vm");
        // missing sessionId

        BoxcutterParams params = new BoxcutterParams(new MapBackedVariables(Map.of(
                "action", "runStep",
                "session", session
        )));

        assertThrows(IllegalArgumentException.class, () -> common.execute(params));
    }

    @Test
    void testRunStepRequiresEntryPointOrCommand() {
        BoxcutterTaskCommon common = new BoxcutterTaskCommon("orch", new MetadataMessageClient("http://localhost:1"));

        Map<String, Object> session = new HashMap<>();
        session.put("vmName", "test-vm");
        session.put("sessionId", "session-123");

        Map<String, Object> vars = new HashMap<>();
        vars.put("action", "runStep");
        vars.put("session", session);
        // missing both entryPoint and command

        BoxcutterParams params = new BoxcutterParams(new MapBackedVariables(vars));

        assertThrows(IllegalArgumentException.class, () -> common.execute(params));
    }

    @Test
    void testDestroyRequiresVmName() {
        BoxcutterTaskCommon common = new BoxcutterTaskCommon("orch", new MetadataMessageClient("http://localhost:1"));

        BoxcutterParams params = new BoxcutterParams(new MapBackedVariables(Map.of(
                "action", "destroy"
        )));

        assertThrows(IllegalArgumentException.class, () -> common.execute(params));
    }
}
