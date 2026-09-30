package com.tradesentry.agent.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Agent-service's local copy of the event contract.
 *
 * <p>The wire format remains compatible with core-service while each service
 * stays independently deployable. eventId is propagated so downstream
 * consumers can deduplicate retries.
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
