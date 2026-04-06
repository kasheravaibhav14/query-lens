package com.querylens.analysisengine.rules;

import java.util.Map;

public record Finding(
        String ruleId,
        Severity severity,
        String namespace,
        int occurrences,
        Map<String, Object> evidence,
        String suggestion
) {}
