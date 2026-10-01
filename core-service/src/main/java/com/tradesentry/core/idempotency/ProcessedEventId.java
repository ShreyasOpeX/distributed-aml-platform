package com.tradesentry.core.idempotency;

import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.UUID;

@Embeddable
public record ProcessedEventId(UUID eventId, String consumerName) implements Serializable {}
