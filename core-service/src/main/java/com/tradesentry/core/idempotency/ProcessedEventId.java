package com.tradesentry.core.idempotency;
import java.io.Serializable;
import java.util.UUID;
public record ProcessedEventId(UUID eventId, String consumerName) implements Serializable {}
