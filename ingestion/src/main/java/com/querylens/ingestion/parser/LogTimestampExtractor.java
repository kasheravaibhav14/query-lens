package com.querylens.ingestion.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
public class LogTimestampExtractor {

    private final ObjectMapper objectMapper = new ObjectMapper();

    public Instant extract(String logLine) {
        try {
            JsonNode root = objectMapper.readTree(logLine);
            JsonNode tNode = root.get("t");
            if (tNode != null) {
                JsonNode dateNode = tNode.get("$date");
                if (dateNode != null && dateNode.isTextual()) {
                    return Instant.parse(dateNode.asText());
                }
            }
        } catch (Exception ignored) {
            // fallback to receipt time
        }
        return Instant.now();
    }
}
