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

/**
 * Executes boxcutter CLI commands (which are SSH commands to the orchestrator).
 */
public final class BoxcutterCommand {

    private static final Logger log = LoggerFactory.getLogger(BoxcutterCommand.class);

    private static final long DEFAULT_TIMEOUT_MS = 300_000; // 5 minutes

    public static Result exec(String... args) throws Exception {
        return exec(DEFAULT_TIMEOUT_MS, args);
    }

    public static Result exec(long timeoutMs, String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add("/tools/bin/boxcutter");
        for (String a : args) {
            cmd.add(a);
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
            throw new RuntimeException("Boxcutter command timed out after " + timeoutMs + "ms: " + String.join(" ", cmd));
        }

        int exitCode = p.exitValue();
        log.info("exec -> exit code: {}, output length: {}", exitCode, output.length());

        return new Result(exitCode, output);
    }

    public record Result(int exitCode, String output) {

        public void assertSuccess(String context) {
            if (exitCode != 0) {
                throw new RuntimeException(context + ": boxcutter command failed with exit code " + exitCode + ": " + output);
            }
        }
    }

    private BoxcutterCommand() {
    }
}
