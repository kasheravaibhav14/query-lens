package com.querylens.analysisengine.rules;

import com.querylens.analysisengine.model.MongoQueryLog;

import java.util.List;

public interface QueryRule {
    String ruleId();
    List<Finding> evaluate(List<MongoQueryLog> window);
}
