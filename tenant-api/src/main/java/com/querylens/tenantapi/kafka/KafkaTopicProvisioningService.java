package com.querylens.tenantapi.kafka;

import com.querylens.infra.async.QueryLensExecutorService;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaTopicProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(KafkaTopicProvisioningService.class);
    private static final int PARTITIONS = 3;
    private static final short REPLICATION_FACTOR = 1;
    private static final int MAX_RETRIES = 3;
    private static final long RETRY_BASE_DELAY_MS = 1000;

    private final AdminClient adminClient;
    private final QueryLensExecutorService executorService;

    @Autowired
    public KafkaTopicProvisioningService(AdminClient adminClient,
                                          QueryLensExecutorService executorService) {
        this.adminClient = adminClient;
        this.executorService = executorService;
    }

    public void provisionTopicAsync(UUID tenantId) {
        String topicName = "logs." + tenantId;
        executorService.submit(() -> {
            for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
                try {
                    NewTopic topic = new NewTopic(topicName, PARTITIONS, REPLICATION_FACTOR);
                    adminClient.createTopics(List.of(topic)).all().get(10, TimeUnit.SECONDS);
                    log.info("Provisioned Kafka topic: {}", topicName);
                    return null;
                } catch (Exception e) {
                    if (attempt < MAX_RETRIES) {
                        log.warn("Failed to provision topic {} (attempt {}/{}), retrying in {}ms",
                                topicName, attempt, MAX_RETRIES, RETRY_BASE_DELAY_MS * attempt);
                        Thread.sleep(RETRY_BASE_DELAY_MS * attempt);
                    } else {
                        // TODO: replace with durable job interface for guaranteed delivery
                        log.error("Failed to provision Kafka topic {} after {} attempts", topicName, MAX_RETRIES, e);
                    }
                }
            }
            return null;
        });
    }
}
