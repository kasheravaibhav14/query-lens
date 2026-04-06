package com.querylens.infra.async;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Virtual-thread executor for all async background tasks in query-lens modules.
 * One virtual thread is created per submitted task — no pool sizing needed.
 * Particularly beneficial for I/O-bound tasks (ClickHouse writes, rule engine Phase 2 queries).
 */
public class QueryLensExecutorService {

    private final ExecutorService executor;

    public QueryLensExecutorService() {
        this.executor = Executors.newVirtualThreadPerTaskExecutor();
    }

    public void submit(Callable<?> task) {
        executor.submit(task);
    }

    public void shutdown() {
        executor.shutdown();
    }
}
