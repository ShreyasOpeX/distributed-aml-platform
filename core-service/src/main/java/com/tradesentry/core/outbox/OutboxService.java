package com.tradesentry.core.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tradesentry.core.events.model.TransactionEvent;
import org.springframework.stereotype.Service;
import java.time.Instant;

@Service
public class OutboxService {
    private final OutboxEventRepository repository;
    private final ObjectMapper mapper;
    public OutboxService(OutboxEventRepository repository,ObjectMapper mapper){
        this.repository=repository;this.mapper=mapper;
    }
    public void enqueue(String topic,TransactionEvent event){
        try{
            repository.save(new OutboxEvent(event.eventId(),topic,event.accountId(),
                    mapper.writeValueAsString(event),Instant.now()));
        }catch(JsonProcessingException e){
            throw new IllegalStateException("Could not serialize event "+event.eventId(),e);
        }
    }
}
