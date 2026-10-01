package com.tradesentry.core.events.producer;

import com.tradesentry.core.events.model.TransactionEvent;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Kept for downstream pipeline stages. Intake publication is handled by the outbox;
 * this producer is used by consumers that create the next event after successful processing.
 */
@Component
public class TransactionEventProducer {
    private final KafkaTemplate<String,TransactionEvent> kafkaTemplate;
    public TransactionEventProducer(KafkaTemplate<String,TransactionEvent> kafkaTemplate){
        this.kafkaTemplate=kafkaTemplate;
    }
    public void publish(String topic,TransactionEvent event){
        kafkaTemplate.send(topic,event.accountId(),event);
    }
}
