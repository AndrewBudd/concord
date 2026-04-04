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
    public static final String HOST_KEY = "host";
    public static final String VM_TYPE_KEY = "type";
    public static final String VM_NAME_KEY = "name";
    public static final String VCPU_KEY = "vcpu";
    public static final String RAM_KEY = "ram";
    public static final String DISK_KEY = "disk";
    public static final String SESSION_KEY = "session";
    public static final String ENTRY_POINT_KEY = "entryPoint";
    public static final String ARGUMENTS_KEY = "arguments";
    public static final String OUT_VARS_KEY = "outVars";
    public static final String REQUIREMENTS_KEY = "requirements";
    public static final String SSH_KEY_PATH_KEY = "sshKeyPath";
    public static final String AGENT_JAR_PATH_KEY = "agentJarPath";
    public static final String SERVER_API_URL_KEY = "serverApiUrl";
    public static final String SERVER_API_KEY_KEY = "serverApiKey";
    public static final String SESSION_WORK_DIR_KEY = "sessionWorkDir";

    private final Variables variables;

    public BoxcutterParams(Variables variables) {
        this.variables = variables;
    }

    public Action action() {
        String s = variables.assertString(ACTION_KEY);
        return Action.valueOf(s.toUpperCase());
    }

    public String host() {
        return variables.assertString(HOST_KEY);
    }

    public String vmType() {
        return variables.getString(VM_TYPE_KEY, "qemu");
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

    public String sshKeyPath() {
        return variables.getString(SSH_KEY_PATH_KEY, null);
    }

    public String agentJarPath() {
        return variables.getString(AGENT_JAR_PATH_KEY, "/opt/concord/agent/concord-agent.jar");
    }

    public String serverApiUrl() {
        return variables.getString(SERVER_API_URL_KEY, null);
    }

    public String serverApiKey() {
        return variables.getString(SERVER_API_KEY_KEY, null);
    }

    public String sessionWorkDir() {
        return variables.getString(SESSION_WORK_DIR_KEY, "/opt/concord/session-workspace");
    }

    public Map<String, Object> session() {
        return variables.getMap(SESSION_KEY, Collections.emptyMap());
    }

    public String entryPoint() {
        return variables.assertString(ENTRY_POINT_KEY);
    }

    public Map<String, Object> arguments() {
        return variables.getMap(ARGUMENTS_KEY, Collections.emptyMap());
    }

    @SuppressWarnings("unchecked")
    public Collection<String> outVars() {
        return variables.getCollection(OUT_VARS_KEY, Collections.emptyList());
    }

    public Map<String, Object> requirements() {
        return variables.getMap(REQUIREMENTS_KEY, Collections.emptyMap());
    }

    public enum Action {
        START,
        RUNSTEP,
        DESTROY
    }
}
