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

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public final class SshCommand {

    private static final Logger log = LoggerFactory.getLogger(SshCommand.class);

    private static final long DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes

    public static Result exec(String host, String sshKeyPath, String... command) throws Exception {
        return exec(host, sshKeyPath, DEFAULT_TIMEOUT_MS, command);
    }

    public static Result exec(String host, String sshKeyPath, long timeoutMs, String... command) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("ssh");
        cmd.add("-o");
        cmd.add("StrictHostKeyChecking=no");
        cmd.add("-o");
        cmd.add("UserKnownHostsFile=/dev/null");
        cmd.add("-o");
        cmd.add("ConnectTimeout=30");
        if (sshKeyPath != null) {
            cmd.add("-i");
            cmd.add(sshKeyPath);
        }
        cmd.add(host);
        for (String c : command) {
            cmd.add(c);
        }

        log.info("exec -> running: {}", String.join(" ", cmd));

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true);

        Process p = pb.start();

        String output;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
            output = reader.lines().collect(Collectors.joining("\n"));
        }

        boolean finished = p.waitFor(timeoutMs, TimeUnit.MILLISECONDS);
        if (!finished) {
            p.destroyForcibly();
            throw new RuntimeException("SSH command timed out after " + timeoutMs + "ms: " + String.join(" ", cmd));
        }

        int exitCode = p.exitValue();
        log.info("exec -> exit code: {}, output: {}", exitCode, output);

        return new Result(exitCode, output);
    }

    public record Result(int exitCode, String output) {

        public void assertSuccess(String context) {
            if (exitCode != 0) {
                throw new RuntimeException(context + ": SSH command failed with exit code " + exitCode + ": " + output);
            }
        }
    }

    private SshCommand() {
    }
}
