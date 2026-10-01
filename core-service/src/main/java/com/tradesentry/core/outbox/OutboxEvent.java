package com.tradesentry.core.outbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "outbox_events", indexes = @Index(name = "idx_outbox_pending", columnList = "status,created_at"))
public class OutboxEvent {
    @Id
    @Column(nullable = false, updatable = false)
    private UUID id;
    @Column(nullable = false) private String topic;
    @Column(name = "event_key", nullable = false) private String eventKey;
    @Lob @Column(nullable = false, columnDefinition = "text") private String payload;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private OutboxStatus status;
    @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;

    protected OutboxEvent() {}
    public OutboxEvent(UUID id, String topic, String eventKey, String payload, Instant createdAt) {
        this.id=id; this.topic=topic; this.eventKey=eventKey; this.payload=payload;
        this.status=OutboxStatus.PENDING; this.createdAt=createdAt;
    }
    public UUID getId(){return id;} public String getTopic(){return topic;} public String getEventKey(){return eventKey;}
    public String getPayload(){return payload;} public OutboxStatus getStatus(){return status;}
    public void markPublished(Instant at){status=OutboxStatus.PUBLISHED; publishedAt=at;}
}
