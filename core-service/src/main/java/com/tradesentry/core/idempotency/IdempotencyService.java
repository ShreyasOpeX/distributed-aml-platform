package com.tradesentry.core.idempotency;

import org.springframework.dao.DataIntegrityViolationException;
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
        try {
            repository.saveAndFlush(new ProcessedEvent(eventId,consumerName,Instant.now()));
            return true;
        } catch(DataIntegrityViolationException duplicate) {
            return false;
        }
    }
}
