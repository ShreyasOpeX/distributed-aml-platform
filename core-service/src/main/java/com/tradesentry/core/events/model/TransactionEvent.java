package com.tradesentry.core.events.model;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Immutable wire event for the transaction monitoring pipeline.
 *
 * <p>{@code eventId} uniquely identifies this event instance. Consumers can
 * use it as an idempotency key while {@code transactionId} identifies the
 * business transaction.
 */
public record TransactionEvent(
        UUID eventId,
        UUID transactionId,
        String accountId,
        BigDecimal amount,
        String currency,
        String counterpartyCountry,
        String reason,
        String decision,
        Instant occurredAt) {

    public static TransactionEvent ingested(UUID transactionId,
                                            String accountId,
                                            BigDecimal amount,
                                            String currency,
                                            String counterpartyCountry) {
        return new TransactionEvent(
                UUID.randomUUID(),
                transactionId,
                accountId,
                amount,
                currency,
                counterpartyCountry,
                null,
                null,
                Instant.now());
    }

    public TransactionEvent flagged(String reason) {
        return new TransactionEvent(
                UUID.randomUUID(),
                transactionId,
                accountId,
                amount,
                currency,
                counterpartyCountry,
                reason,
                null,
                Instant.now());
    }

    public TransactionEvent adjudicated(String decision, String rationale) {
        return new TransactionEvent(
                UUID.randomUUID(),
                transactionId,
                accountId,
                amount,
                currency,
                counterpartyCountry,
                rationale,
                decision,
                Instant.now());
    }
}
