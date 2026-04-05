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
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

class BoxcutterParamsTest {

    @Test
    void testActionParsing() {
        BoxcutterParams params = params(Map.of("action", "start"));
        assertEquals(BoxcutterParams.Action.START, params.action());

        params = params(Map.of("action", "RUNSTEP"));
        assertEquals(BoxcutterParams.Action.RUNSTEP, params.action());

        params = params(Map.of("action", "Destroy"));
        assertEquals(BoxcutterParams.Action.DESTROY, params.action());
    }

    @Test
    void testActionRequired() {
        BoxcutterParams params = params(Map.of());
        assertThrows(IllegalArgumentException.class, params::action);
    }

    @Test
    void testDefaults() {
        BoxcutterParams params = params(Map.of("action", "start"));
        assertEquals("firecracker", params.vmType());
        assertNull(params.vmName());
        assertEquals(2, params.vcpu());
        assertEquals(2048, params.ram());
        assertEquals("50G", params.disk());
        assertEquals(300_000L, params.timeout());
        assertEquals("http://169.254.169.254", params.metadataUrl());
        assertTrue(params.session().isEmpty());
        assertTrue(params.arguments().isEmpty());
        assertTrue(params.outVars().isEmpty());
    }

    @Test
    void testCustomValues() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("action", "start");
        vars.put("type", "qemu");
        vars.put("name", "my-vm");
        vars.put("vcpu", 4);
        vars.put("ram", 8192);
        vars.put("disk", "100G");
        vars.put("timeout", 600000L);
        vars.put("metadataUrl", "http://custom:1234");

        BoxcutterParams params = params(vars);
        assertEquals("qemu", params.vmType());
        assertEquals("my-vm", params.vmName());
        assertEquals(4, params.vcpu());
        assertEquals(8192, params.ram());
        assertEquals("100G", params.disk());
        assertEquals(600000L, params.timeout());
        assertEquals("http://custom:1234", params.metadataUrl());
    }

    @Test
    void testSessionParsing() {
        Map<String, Object> session = new HashMap<>();
        session.put("sessionId", "test-session-123");
        session.put("vmName", "test-vm");
        session.put("orchestratorVm", "my-vm");

        BoxcutterParams params = params(Map.of(
                "action", "runStep",
                "session", session
        ));

        assertEquals("test-session-123", params.session().get("sessionId"));
        assertEquals("test-vm", params.session().get("vmName"));
    }

    @Test
    void testEntryPointAndCommand() {
        BoxcutterParams params = params(Map.of(
                "action", "runStep",
                "entryPoint", "deploy"
        ));
        assertEquals("deploy", params.entryPoint());
        assertNull(params.command());

        params = params(Map.of(
                "action", "runStep",
                "command", "echo hello"
        ));
        assertNull(params.entryPoint());
        assertEquals("echo hello", params.command());
    }

    @Test
    void testArguments() {
        Map<String, Object> args = Map.of("appVersion", "2.0", "env", "staging");
        BoxcutterParams params = params(Map.of(
                "action", "runStep",
                "arguments", args
        ));

        assertEquals("2.0", params.arguments().get("appVersion"));
        assertEquals("staging", params.arguments().get("env"));
    }

    @Test
    void testOutVars() {
        BoxcutterParams params = params(Map.of(
                "action", "runStep",
                "outVars", List.of("result", "status")
        ));

        Collection<String> outVars = params.outVars();
        assertEquals(2, outVars.size());
        assertTrue(outVars.contains("result"));
        assertTrue(outVars.contains("status"));
    }

    private static BoxcutterParams params(Map<String, Object> vars) {
        return new BoxcutterParams(new MapBackedVariables(vars));
    }
}
