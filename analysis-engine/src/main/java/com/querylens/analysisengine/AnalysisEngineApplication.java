package com.querylens.analysisengine;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableScheduling
public class AnalysisEngineApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnalysisEngineApplication.class, args);
    }
}
