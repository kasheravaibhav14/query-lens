package com.querylens.analysisengine.clickhouse;

import com.querylens.analysisengine.model.MongoQueryLog;
import com.querylens.infra.async.QueryLensExecutorService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;

@Component
public class ClickHouseWriteBuffer {

    private final ConcurrentLinkedQueue<MongoQueryLog> buffer = new ConcurrentLinkedQueue<>();
    private final MongoLogEventRepository repository;
    private final QueryLensExecutorService executorService;

    @Autowired
    public ClickHouseWriteBuffer(Optional<MongoLogEventRepository> repository,
                                 QueryLensExecutorService executorService) {
        this.repository = repository.orElse(null);
        this.executorService = executorService;
    }

    public void add(MongoQueryLog log) {
        buffer.add(log);
    }

    @Scheduled(fixedDelayString = "${query-lens.clickhouse.flush-interval-millis:1000}")
    public void flush() {
        if (repository == null) {
            return;
        }
        List<MongoQueryLog> batch = new ArrayList<>();
        MongoQueryLog log;
        while ((log = buffer.poll()) != null) {
            batch.add(log);
        }
        if (!batch.isEmpty()) {
            executorService.submit(() -> { repository.insertBatch(batch); return null; });
        }
    }
}
