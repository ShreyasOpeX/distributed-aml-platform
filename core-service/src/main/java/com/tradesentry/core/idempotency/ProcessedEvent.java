package com.tradesentry.core.idempotency;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name="processed_events")
public class ProcessedEvent {
    @EmbeddedId private ProcessedEventId id;
    @Column(name="processed_at", nullable=false) private Instant processedAt;
    protected ProcessedEvent(){}
    public ProcessedEvent(UUID eventId,String consumerName,Instant processedAt){
        this.id=new ProcessedEventId(eventId,consumerName); this.processedAt=processedAt;
    }
}
