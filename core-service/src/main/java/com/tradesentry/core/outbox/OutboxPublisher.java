package com.tradesentry.core.outbox;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Component
public class OutboxPublisher {
    private final OutboxEventRepository repository;
    private final KafkaTemplate<String,String> kafkaTemplate;
    public OutboxPublisher(OutboxEventRepository repository,KafkaTemplate<String,String> kafkaTemplate){
        this.repository=repository; this.kafkaTemplate=kafkaTemplate;
    }

    @Scheduled(fixedDelayString="${tradesentry.outbox.poll-ms:500}")
    @Transactional
    public void publishPending(){
        List<OutboxEvent> events=repository.findTop100ByStatusOrderByCreatedAtAsc(OutboxStatus.PENDING);
        for(OutboxEvent event:events){
            try{
                kafkaTemplate.send(event.getTopic(),event.getEventKey(),event.getPayload()).get(10,TimeUnit.SECONDS);
                event.markPublished(Instant.now());
            }catch(Exception ex){
                throw new IllegalStateException("Outbox publish failed for "+event.getId(),ex);
            }
        }
    }
}
