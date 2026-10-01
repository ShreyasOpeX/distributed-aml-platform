package com.tradesentry.core.events.config;

import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.*;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.*;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaErrorHandlingConfig {
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, com.tradesentry.core.events.model.TransactionEvent> template) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                template,
                (record, ex) -> new TopicPartition(KafkaTopics.DLQ, 0));
        return new DefaultErrorHandler(recoverer, new FixedBackOff(1000L, 2L));
    }
}
