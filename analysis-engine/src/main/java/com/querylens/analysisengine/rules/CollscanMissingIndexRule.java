package com.querylens.analysisengine.rules;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects collection scans with no index usage on high-volume namespaces.
 *
 * Trigger: planSummary = "COLLSCAN" AND docsExamined >= minDocsExamined
 *          AND durationMillis >= minDurationMillis
 *          AND >= minOccurrences on the same namespace in the window.
 */
@Component
public class CollscanMissingIndexRule implements QueryRule {

    private final int minDocsExamined;
    private final long minDurationMillis;
    private final int minOccurrences;

    public CollscanMissingIndexRule(
            @Value("${query-lens.rules.collscan.min-docs-examined:500}") int minDocsExamined,
            @Value("${query-lens.rules.collscan.min-duration-millis:100}") long minDurationMillis,
            @Value("${query-lens.rules.collscan.min-occurrences:2}") int minOccurrences) {
        this.minDocsExamined = minDocsExamined;
        this.minDurationMillis = minDurationMillis;
        this.minOccurrences = minOccurrences;
    }

    @Override
    public String ruleId() {
        return "collscan_missing_index";
    }

    @Override
    public List<Finding> evaluate(List<MongoQueryLog> window) {
        Map<String, List<MongoQueryLog>> byNamespace = window.stream()
                .filter(log -> "COLLSCAN".equals(log.planSummary()))
                .filter(log -> log.docsExamined() >= minDocsExamined)
                .filter(log -> log.durationMillis() >= minDurationMillis)
                .collect(Collectors.groupingBy(log -> log.namespace() != null ? log.namespace() : "unknown"));

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<MongoQueryLog>> entry : byNamespace.entrySet()) {
            List<MongoQueryLog> hits = entry.getValue();
            if (hits.size() < minOccurrences) continue;

            long avgDuration = (long) hits.stream().mapToLong(MongoQueryLog::durationMillis).average().orElse(0);
            long avgDocsExamined = (long) hits.stream().mapToInt(MongoQueryLog::docsExamined).average().orElse(0);
            long avgNreturned = (long) hits.stream().mapToInt(MongoQueryLog::nreturned).average().orElse(0);

            findings.add(new Finding(
                    ruleId(),
                    Severity.HIGH,
                    entry.getKey(),
                    hits.size(),
                    Map.of(
                            "avgDurationMillis", avgDuration,
                            "avgDocsExamined", avgDocsExamined,
                            "avgNreturned", avgNreturned
                    ),
                    String.format(
                            "COLLSCAN on %s — no index used. Scanned ~%d docs per query, returned ~%d. Add an index matching the query filter.",
                            entry.getKey(), avgDocsExamined, avgNreturned)
            ));
        }
        return findings;
    }
}
