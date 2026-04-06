package com.querylens.analysisengine.extractor;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.querylens.analysisengine.model.MongoQueryLog;
import com.querylens.infra.model.DatabaseType;
import com.querylens.infra.model.LogEnvelope;
import com.querylens.infra.model.QueryLogExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
public class MongoQueryLogExtractor extends QueryLogExtractor<MongoQueryLog> {

    private final ObjectMapper objectMapper;

    @Autowired
    public MongoQueryLogExtractor(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public DatabaseType dbType() {
        return DatabaseType.MONGODB;
    }

    @Override
    public Optional<MongoQueryLog> extract(LogEnvelope envelope) {
        try {
            JsonNode root = objectMapper.readTree(envelope.rawPayload());
            JsonNode attr = root.get("attr");
            if (attr == null || !attr.has("durationMillis")) {
                return Optional.empty();
            }
            return Optional.of(new MongoQueryLog(
                    envelope.tenantId(),
                    DatabaseType.MONGODB,
                    envelope.eventTime(),
                    envelope.receivedAt(),
                    attr.get("durationMillis").asLong(),
                    attr.has("ns") ? attr.get("ns").asText() : null,
                    envelope.source(),
                    attr.has("planSummary") ? attr.get("planSummary").asText() : null,
                    attr.has("keysExamined") ? attr.get("keysExamined").asInt() : 0,
                    attr.has("docsExamined") ? attr.get("docsExamined").asInt() : 0,
                    attr.has("nreturned") ? attr.get("nreturned").asInt() : 0,
                    attr.has("command") ? attr.get("command").toString() : null,
                    root.has("ctx") ? root.get("ctx").asText() : null
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
