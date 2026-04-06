package com.querylens.analysisengine.rules;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Detects queries with an index that is not selective enough.
 * An index exists (IXSCAN) but many index entries are scanned per returned document,
 * indicating a low-cardinality index (e.g., indexed on a boolean or low-enum field).
 *
 * Trigger: planSummary contains "IXSCAN"
 *          AND keysExamined / max(nreturned, 1) >= minSelectivityRatio
 *          AND durationMillis >= minDurationMillis
 *          AND >= minOccurrences on the same namespace in the window.
 */
@Component
public class PoorIndexSelectivityRule implements QueryRule {

    private final int minSelectivityRatio;
    private final long minDurationMillis;
    private final int minOccurrences;

    public PoorIndexSelectivityRule(
            @Value("${query-lens.rules.selectivity.min-ratio:50}") int minSelectivityRatio,
            @Value("${query-lens.rules.selectivity.min-duration-millis:100}") long minDurationMillis,
            @Value("${query-lens.rules.selectivity.min-occurrences:2}") int minOccurrences) {
        this.minSelectivityRatio = minSelectivityRatio;
        this.minDurationMillis = minDurationMillis;
        this.minOccurrences = minOccurrences;
    }

    @Override
    public String ruleId() {
        return "poor_index_selectivity";
    }

    @Override
    public List<Finding> evaluate(List<MongoQueryLog> window) {
        Map<String, List<MongoQueryLog>> byNamespace = window.stream()
                .filter(log -> log.planSummary() != null && log.planSummary().contains("IXSCAN"))
                .filter(log -> log.durationMillis() >= minDurationMillis)
                .filter(log -> {
                    int effective = Math.max(log.nreturned(), 1);
                    return log.keysExamined() / effective >= minSelectivityRatio;
                })
                .collect(Collectors.groupingBy(log -> log.namespace() != null ? log.namespace() : "unknown"));

        List<Finding> findings = new ArrayList<>();
        for (Map.Entry<String, List<MongoQueryLog>> entry : byNamespace.entrySet()) {
            List<MongoQueryLog> hits = entry.getValue();
            if (hits.size() < minOccurrences) continue;

            long avgDuration = (long) hits.stream().mapToLong(MongoQueryLog::durationMillis).average().orElse(0);
            long avgKeysExamined = (long) hits.stream().mapToInt(MongoQueryLog::keysExamined).average().orElse(0);
            long avgNreturned = (long) hits.stream().mapToInt(MongoQueryLog::nreturned).average().orElse(0);
            long avgRatio = avgNreturned > 0 ? avgKeysExamined / avgNreturned : avgKeysExamined;

            findings.add(new Finding(
                    ruleId(),
                    Severity.MEDIUM,
                    entry.getKey(),
                    hits.size(),
                    Map.of(
                            "avgDurationMillis", avgDuration,
                            "avgKeysExamined", avgKeysExamined,
                            "avgNreturned", avgNreturned,
                            "avgSelectivityRatio", avgRatio
                    ),
                    String.format(
                            "Poor index selectivity on %s — index scans ~%d keys to return ~%d docs (ratio %d:1). Consider a more selective or compound index.",
                            entry.getKey(), avgKeysExamined, avgNreturned, avgRatio)
            ));
        }
        return findings;
    }
}
