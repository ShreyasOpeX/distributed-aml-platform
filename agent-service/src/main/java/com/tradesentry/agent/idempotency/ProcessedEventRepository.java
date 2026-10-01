package com.tradesentry.agent.idempotency;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,ProcessedEventId>{
    default boolean claim(java.util.UUID eventId,String consumer){try{saveAndFlush(new ProcessedEvent(eventId,consumer));return true;}catch(org.springframework.dao.DataIntegrityViolationException e){return false;}}
}
