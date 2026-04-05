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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

class BoxcutterCommandTest {

    @Test
    @EnabledIfEnvironmentVariable(named = "BOXCUTTER_TEST_ENABLED", matches = "true")
    void testListVms() throws Exception {
        BoxcutterCommand.Result result = BoxcutterCommand.exec("list");
        assertEquals(0, result.exitCode());
        assertNotNull(result.output());
        // The list command should return something (header at minimum)
        assertFalse(result.output().isEmpty());
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BOXCUTTER_TEST_ENABLED", matches = "true")
    void testHelp() throws Exception {
        BoxcutterCommand.Result result = BoxcutterCommand.exec("help");
        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("Boxcutter"));
    }

    @Test
    void testResultAssertSuccess() {
        BoxcutterCommand.Result success = new BoxcutterCommand.Result(0, "ok");
        assertDoesNotThrow(() -> success.assertSuccess("test"));

        BoxcutterCommand.Result failure = new BoxcutterCommand.Result(1, "error msg");
        RuntimeException ex = assertThrows(RuntimeException.class, () -> failure.assertSuccess("test context"));
        assertTrue(ex.getMessage().contains("test context"));
        assertTrue(ex.getMessage().contains("error msg"));
    }
}
