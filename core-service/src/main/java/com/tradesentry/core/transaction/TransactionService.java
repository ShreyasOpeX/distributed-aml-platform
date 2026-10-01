package com.tradesentry.core.transaction;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesentry.core.events.config.KafkaTopics;
import com.tradesentry.core.events.model.TransactionEvent;
import com.tradesentry.core.outbox.OutboxEvent;
import com.tradesentry.core.outbox.OutboxEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Service
public class TransactionService {
    private final TransactionRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TransactionService(TransactionRepository repository,OutboxEventRepository outboxRepository,ObjectMapper objectMapper){
        this.repository=repository; this.outboxRepository=outboxRepository; this.objectMapper=objectMapper;
    }

    @Transactional
    public UUID submit(TransactionRequest request){
        UUID id=UUID.randomUUID();
        Instant now=Instant.now();
        repository.save(new Transaction(id,request.accountId(),request.amount(),request.currency(),
                request.counterpartyCountry(),TransactionStatus.SUBMITTED,now,now));

        TransactionEvent event=TransactionEvent.ingested(id,request.accountId(),request.amount(),
                request.currency(),request.counterpartyCountry());
        try{
            String payload=objectMapper.writeValueAsString(event);
            outboxRepository.save(new OutboxEvent(event.eventId(),KafkaTopics.INGESTED,
                    event.accountId(),payload,Instant.now()));
        }catch(JsonProcessingException e){
            throw new IllegalStateException("Could not serialize transaction event",e);
        }
        return id;
    }
}
