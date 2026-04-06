package com.querylens.analysisengine.store;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

@Component
public class InMemoryWindowStore {

    private final ConcurrentHashMap<UUID, ConcurrentLinkedDeque<MongoQueryLog>> store =
            new ConcurrentHashMap<>();
    private final long windowDurationMillis;

    public InMemoryWindowStore(
            @Value("${query-lens.window.duration-millis:1000}") long windowDurationMillis) {
        this.windowDurationMillis = windowDurationMillis;
    }

    public void add(MongoQueryLog log) {
        store.computeIfAbsent(log.tenantId(), k -> new ConcurrentLinkedDeque<>())
             .addLast(log);
    }

    public List<MongoQueryLog> getWindow(UUID tenantId) {
        Instant cutoff = Instant.now().minusMillis(windowDurationMillis);
        ConcurrentLinkedDeque<MongoQueryLog> deque = store.getOrDefault(
                tenantId, new ConcurrentLinkedDeque<>());
        while (!deque.isEmpty() && deque.peekFirst().timestamp().isBefore(cutoff)) {
            deque.pollFirst();
        }
        return List.copyOf(deque);
    }
}
