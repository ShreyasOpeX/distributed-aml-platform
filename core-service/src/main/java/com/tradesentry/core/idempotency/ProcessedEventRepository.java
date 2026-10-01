package com.tradesentry.core.idempotency;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent,ProcessedEventId> {}
