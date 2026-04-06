package com.querylens.infra.codec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Primary;

@Primary
public class JsonEventCodec implements EventCodec {

    private final ObjectMapper objectMapper;

    public JsonEventCodec() {
        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule());
    }

    @Override
    public byte[] serialize(Object event) {
        try {
            return objectMapper.writeValueAsBytes(event);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize event", e);
        }
    }

    @Override
    public <T> T deserialize(byte[] bytes, Class<T> type) {
        try {
            return objectMapper.readValue(bytes, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize event", e);
        }
    }
}
