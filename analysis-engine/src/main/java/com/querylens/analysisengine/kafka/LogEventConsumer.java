package com.querylens.analysisengine.kafka;

import com.querylens.analysisengine.clickhouse.ClickHouseWriteBuffer;
import com.querylens.analysisengine.model.MongoQueryLog;
import com.querylens.analysisengine.store.InMemoryWindowStore;
import com.querylens.infra.codec.EventCodec;
import com.querylens.infra.model.DatabaseType;
import com.querylens.infra.model.LogEnvelope;
import com.querylens.infra.model.QueryLog;
import com.querylens.infra.model.QueryLogExtractor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class LogEventConsumer {

    private final Map<DatabaseType, QueryLogExtractor<QueryLog>> extractors;
    private final EventCodec eventCodec;
    private final InMemoryWindowStore windowStore;
    private final ClickHouseWriteBuffer writeBuffer;

    @Autowired
    public LogEventConsumer(List<QueryLogExtractor<QueryLog>> extractorList,
                            EventCodec eventCodec,
                            InMemoryWindowStore windowStore,
                            ClickHouseWriteBuffer writeBuffer) {
        this.extractors = extractorList.stream()
                .collect(Collectors.toMap(QueryLogExtractor::dbType, Function.identity()));
        this.eventCodec = eventCodec;
        this.windowStore = windowStore;
        this.writeBuffer = writeBuffer;
    }

    @KafkaListener(topicPattern = "logs\\..*", groupId = "${spring.kafka.consumer.group-id}")
    public void consume(byte[] message) {
        LogEnvelope envelope = eventCodec.deserialize(message, LogEnvelope.class);
        QueryLogExtractor<?> extractor = extractors.get(envelope.dbType());
        if (extractor == null) {
            return;
        }
        extractor.extract(envelope).ifPresent(queryLog -> {
            if (queryLog instanceof MongoQueryLog mongoQueryLog) {
                windowStore.add(mongoQueryLog);
                writeBuffer.add(mongoQueryLog);
            }
            // TODO: add cases for future db types (PostgresQueryLog, etc.)
        });
    }
}
