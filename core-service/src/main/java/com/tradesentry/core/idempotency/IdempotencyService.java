package com.tradesentry.core.idempotency;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.UUID;

@Service
public class IdempotencyService {
    private final ProcessedEventRepository repository;
    public IdempotencyService(ProcessedEventRepository repository){this.repository=repository;}
    @Transactional
    public boolean claim(UUID eventId,String consumerName){
        return repository.insertIfAbsent(eventId,consumerName,Instant.now())==1;
    }
}
