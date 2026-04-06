package com.querylens.analysisengine.rules;

import com.querylens.analysisengine.model.MongoQueryLog;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class RuleEngine {

    private final List<QueryRule> rules;

    @Autowired
    public RuleEngine(List<QueryRule> rules) {
        this.rules = rules;
    }

    public List<Finding> evaluate(List<MongoQueryLog> window) {
        return rules.stream()
                .flatMap(rule -> rule.evaluate(window).stream())
                .toList();
    }
}
