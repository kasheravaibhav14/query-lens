package com.querylens.ingestion.kafka;

import com.querylens.infra.codec.EventCodec;
import com.querylens.infra.model.LogEnvelope;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaProducerService {

    private final KafkaTemplate<String, byte[]> kafkaTemplate;
    private final EventCodec eventCodec;

    @Autowired
    public KafkaProducerService(KafkaTemplate<String, byte[]> kafkaTemplate, EventCodec eventCodec) {
        this.kafkaTemplate = kafkaTemplate;
        this.eventCodec = eventCodec;
    }

    public void send(LogEnvelope envelope) {
        String topic = "logs." + envelope.tenantId();
        byte[] payload = eventCodec.serialize(envelope);
        kafkaTemplate.send(topic, envelope.tenantId().toString(), payload);
    }
}
