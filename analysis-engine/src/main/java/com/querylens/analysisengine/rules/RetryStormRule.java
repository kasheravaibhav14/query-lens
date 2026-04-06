package com.querylens.analysisengine.rules;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects a high-frequency burst of queries against the same namespace.
 * Indicates an N+1 loop, application retry without backoff, or runaway batch job.
 *
 * Trigger: >= minOccurrences queries against the same namespace in the window,
 *          regardless of duration or plan.
 */
@Component
public class RetryStormRule implements QueryRule {

    private final int minOccurrences;

    public RetryStormRule(
            @Value("${query-lens.rules.retry-storm.min-occurrences:10}") int minOccurrences) {
        this.minOccurrences = minOccurrences;
    }

    @Override
    public String ruleId() {
        return "retry_storm";
    }

    @Override
    public List<Finding> evaluate(List<MongoQueryLog> window) {
        Map<String, List<MongoQueryLog>> byNamespace = window.stream()
                .filter(log -> log.namespace() != null)
                .collect(Collectors.groupingBy(MongoQueryLog::namespace));

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<MongoQueryLog>> entry : byNamespace.entrySet()) {
            List<MongoQueryLog> hits = entry.getValue();
            if (hits.size() < minOccurrences) continue;

            long avgDuration = (long) hits.stream().mapToLong(MongoQueryLog::durationMillis).average().orElse(0);
            long distinctServices = hits.stream()
                    .map(MongoQueryLog::service)
                    .filter(s -> s != null && !s.isBlank())
                    .distinct()
                    .count();

            findings.add(new Finding(
                    ruleId(),
                    Severity.HIGH,
                    entry.getKey(),
                    hits.size(),
                    Map.of(
                            "avgDurationMillis", avgDuration,
                            "distinctServices", distinctServices
                    ),
                    String.format(
                            "%d queries to %s in the evaluation window — possible N+1, retry storm, or runaway batch. Examine application query patterns.",
                            hits.size(), entry.getKey())
            ));
        }
        return findings;
    }
}
