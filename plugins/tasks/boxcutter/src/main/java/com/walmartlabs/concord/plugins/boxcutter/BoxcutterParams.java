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

import com.walmartlabs.concord.runtime.v2.sdk.Variables;

import java.util.*;

public class BoxcutterParams {

    public static final String ACTION_KEY = "action";
    public static final String VM_TYPE_KEY = "type";
    public static final String VM_NAME_KEY = "name";
    public static final String VCPU_KEY = "vcpu";
    public static final String RAM_KEY = "ram";
    public static final String DISK_KEY = "disk";
    public static final String SESSION_KEY = "session";
    public static final String ENTRY_POINT_KEY = "entryPoint";
    public static final String ARGUMENTS_KEY = "arguments";
    public static final String OUT_VARS_KEY = "outVars";
    public static final String COMMAND_KEY = "command";
    public static final String TIMEOUT_KEY = "timeout";
    public static final String METADATA_URL_KEY = "metadataUrl";

    private final Variables variables;

    public BoxcutterParams(Variables variables) {
        this.variables = variables;
    }

    public Action action() {
        String s = variables.assertString(ACTION_KEY);
        return Action.valueOf(s.toUpperCase());
    }

    public String vmType() {
        return variables.getString(VM_TYPE_KEY, "firecracker");
    }

    public String vmName() {
        return variables.getString(VM_NAME_KEY, null);
    }

    public int vcpu() {
        return variables.getInt(VCPU_KEY, 2);
    }

    public int ram() {
        return variables.getInt(RAM_KEY, 2048);
    }

    public String disk() {
        return variables.getString(DISK_KEY, "50G");
    }

    public Map<String, Object> session() {
        return variables.getMap(SESSION_KEY, Collections.emptyMap());
    }

    public String entryPoint() {
        return variables.getString(ENTRY_POINT_KEY, null);
    }

    public String command() {
        return variables.getString(COMMAND_KEY, null);
    }

    public Map<String, Object> arguments() {
        return variables.getMap(ARGUMENTS_KEY, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    public Collection<String> outVars() {
        return variables.getCollection(OUT_VARS_KEY, Collections.emptyList());
    }

    public long timeout() {
        return variables.getLong(TIMEOUT_KEY, 300_000L);
    }

    public String metadataUrl() {
        return variables.getString(METADATA_URL_KEY, "http://169.254.169.254");
    }

    public enum Action {
        START,
        RUNSTEP,
        DESTROY
    }
}
