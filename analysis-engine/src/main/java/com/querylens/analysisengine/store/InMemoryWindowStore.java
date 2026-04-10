package com.querylens.analysisengine.store;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * Single-replica constraint: this store is JVM-local. Running more than one analysis-engine
 * replica will split Kafka partition assignments across instances, meaning each instance only
 * sees a fraction of a tenant's logs. GET /analyze on a replica that holds no logs for a tenant
 * will return empty findings. If horizontal scaling is ever needed, replace this with a
 * co-located state store (e.g. Kafka Streams) or an external store (e.g. Redis sorted sets).
 */
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
