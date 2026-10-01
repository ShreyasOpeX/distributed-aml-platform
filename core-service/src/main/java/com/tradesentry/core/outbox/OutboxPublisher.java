package com.tradesentry.core.outbox;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String,Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public OutboxPublisher(OutboxEventRepository repository,
                           @Qualifier("outboxKafkaTemplate") KafkaTemplate<String,Object> kafkaTemplate,
                           ObjectMapper objectMapper){
        this.repository=repository; this.kafkaTemplate=kafkaTemplate; this.objectMapper=objectMapper;
    }

    @Scheduled(fixedDelayString="\${tradesentry.outbox.poll-ms:500}")
    @Transactional
    public void publishPending(){
        List<OutboxEvent> events=repository.lockPendingBatch();
        for(OutboxEvent event:events){
            try{
                JsonNode payload=objectMapper.readTree(event.getPayload());
                kafkaTemplate.send(event.getTopic(),event.getEventKey(),payload).get(10,TimeUnit.SECONDS);
                event.markPublished(Instant.now());
            }catch(Exception ex){
                throw new IllegalStateException("Outbox publish failed for "+event.getId(),ex);
            }
        }
    }
}
