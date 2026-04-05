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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Client for the boxcutter metadata service messaging API.
 * Allows sending messages to VMs and reading messages from our inbox.
 */
public class MetadataMessageClient {

    private static final Logger log = LoggerFactory.getLogger(MetadataMessageClient.class);

    private static final ObjectMapper objectMapper = new ObjectMapper();

    private final String baseUrl;
    private final HttpClient httpClient;

    public MetadataMessageClient(String baseUrl) {
        this.baseUrl = baseUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * Send a message to a target VM.
     *
     * @return the message ID
     */
    public String send(String to, String subject, String body) throws IOException, InterruptedException {
        Map<String, String> payload = Map.of(
                "to", to,
                "subject", subject,
                "body", body
        );

        String json = objectMapper.writeValueAsString(payload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages/send"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200 && response.statusCode() != 201) {
            throw new IOException("Failed to send message (HTTP " + response.statusCode() + "): " + response.body());
        }

        Map<String, Object> result = objectMapper.readValue(response.body(), new TypeReference<>() {});
        String msgId = (String) result.get("id");
        log.debug("Sent message to {} (id={})", to, msgId);
        return msgId;
    }

    /**
     * Read all pending messages from our inbox.
     * Messages are marked in-flight for 30 seconds.
     */
    public List<Message> readMessages() throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages"))
                .GET()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            throw new IOException("Failed to read messages (HTTP " + response.statusCode() + "): " + response.body());
        }

        String body = response.body();
        if (body == null || body.isBlank() || "[]".equals(body.trim())) {
            return Collections.emptyList();
        }

        List<Map<String, Object>> raw = objectMapper.readValue(body, new TypeReference<>() {});
        return raw.stream()
                .map(m -> new Message(
                        (String) m.get("id"),
                        (String) m.get("from"),
                        (String) m.get("to"),
                        (String) m.get("subject"),
                        (String) m.get("body")
                ))
                .toList();
    }

    /**
     * Acknowledge (delete) a message by ID.
     */
    public void acknowledge(String messageId) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/messages/" + messageId))
                .DELETE()
                .timeout(Duration.ofSeconds(30))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 204 && response.statusCode() != 200) {
            throw new IOException("Failed to acknowledge message " + messageId +
                    " (HTTP " + response.statusCode() + "): " + response.body());
        }
        log.debug("Acknowledged message {}", messageId);
    }

    public record Message(String id, String from, String to, String subject, String body) {
    }
}
