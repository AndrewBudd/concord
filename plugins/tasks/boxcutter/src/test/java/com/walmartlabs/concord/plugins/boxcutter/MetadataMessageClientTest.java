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

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for MetadataMessageClient. The integration tests require
 * the metadata service to be available (run inside a boxcutter VM).
 */
class MetadataMessageClientTest {

    private static final String METADATA_URL = "http://169.254.169.254";

    @Test
    @EnabledIfEnvironmentVariable(named = "BOXCUTTER_TEST_ENABLED", matches = "true")
    void testSendAndReceive() throws Exception {
        MetadataMessageClient client = new MetadataMessageClient(METADATA_URL);

        // Get our hostname for self-messaging
        String hostname = getHostname();

        // Send a test message to ourselves
        String msgId = client.send(hostname, "test-subject", "test-body-content");
        assertNotNull(msgId);
        assertFalse(msgId.isEmpty());

        // Read messages - should contain our test message
        List<MetadataMessageClient.Message> messages = client.readMessages();
        assertFalse(messages.isEmpty());

        MetadataMessageClient.Message found = messages.stream()
                .filter(m -> "test-subject".equals(m.subject()) && "test-body-content".equals(m.body()))
                .findFirst()
                .orElse(null);
        assertNotNull(found, "Should find our test message");
        assertEquals(hostname, found.from());

        // Acknowledge it
        client.acknowledge(found.id());

        // Verify it's gone (after in-flight timeout or immediate for acked)
        // Send another message and verify we only get that one
        String msgId2 = client.send(hostname, "test-subject-2", "body-2");
        List<MetadataMessageClient.Message> remaining = client.readMessages();

        boolean foundOriginal = remaining.stream()
                .anyMatch(m -> "test-body-content".equals(m.body()));
        assertFalse(foundOriginal, "Acknowledged message should not reappear");

        // Clean up
        for (MetadataMessageClient.Message m : remaining) {
            client.acknowledge(m.id());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BOXCUTTER_TEST_ENABLED", matches = "true")
    void testSendJsonBody() throws Exception {
        MetadataMessageClient client = new MetadataMessageClient(METADATA_URL);
        String hostname = getHostname();

        String jsonBody = "{\"type\":\"test\",\"data\":{\"key\":\"value\",\"num\":42}}";
        client.send(hostname, "json-test", jsonBody);

        List<MetadataMessageClient.Message> messages = client.readMessages();
        MetadataMessageClient.Message found = messages.stream()
                .filter(m -> "json-test".equals(m.subject()))
                .findFirst()
                .orElse(null);

        assertNotNull(found);
        assertEquals(jsonBody, found.body());

        // Clean up
        for (MetadataMessageClient.Message m : messages) {
            client.acknowledge(m.id());
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "BOXCUTTER_TEST_ENABLED", matches = "true")
    void testEmptyInbox() throws Exception {
        MetadataMessageClient client = new MetadataMessageClient(METADATA_URL);

        // First drain any existing messages
        List<MetadataMessageClient.Message> existing = client.readMessages();
        for (MetadataMessageClient.Message m : existing) {
            client.acknowledge(m.id());
        }

        // Wait for in-flight to expire
        Thread.sleep(31_000);

        List<MetadataMessageClient.Message> messages = client.readMessages();
        assertTrue(messages.isEmpty());
    }

    @Test
    void testInvalidUrl() {
        MetadataMessageClient client = new MetadataMessageClient("http://192.0.2.1:1");
        assertThrows(Exception.class, () -> client.send("target", "subj", "body"));
    }

    private static String getHostname() throws Exception {
        ProcessBuilder pb = new ProcessBuilder("hostname");
        Process p = pb.start();
        String hostname = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return hostname;
    }
}
