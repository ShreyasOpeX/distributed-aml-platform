package com.tradesentry.core.events.consumer;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesentry.core.audit.*;
import com.tradesentry.core.events.config.KafkaTopics;
import com.tradesentry.core.events.model.TransactionEvent;
import com.tradesentry.core.idempotency.IdempotencyService;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class AuditConsumer {
    private final AuditRecordRepository repository;
    private final IdempotencyService idempotency;
    private final ObjectMapper mapper;

    public AuditConsumer(AuditRecordRepository repository,IdempotencyService idempotency,ObjectMapper mapper){
        this.repository=repository;this.idempotency=idempotency;this.mapper=mapper;
    }

    @KafkaListener(topics=KafkaTopics.INGESTED,groupId="audit")
    @Transactional
    public void onIngested(TransactionEvent event){record(event,"TRANSACTION_INGESTED");}

    @KafkaListener(topics=KafkaTopics.ADJUDICATED,groupId="audit")
    @Transactional
    public void onAdjudicated(TransactionEvent event){record(event,"TRANSACTION_ADJUDICATED");}

    private void record(TransactionEvent event,String type){
        if(!idempotency.claim(event.eventId(),"audit")) return;
        try{
            repository.save(new AuditRecord(event.eventId(),event.transactionId(),event.accountId(),type,
                    mapper.writeValueAsString(event),event.occurredAt()));
        }catch(JsonProcessingException e){throw new IllegalStateException("Audit serialization failed",e);}
    }
}
