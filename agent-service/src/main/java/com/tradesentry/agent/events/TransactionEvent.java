package com.tradesentry.agent.events;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionEvent(
        UUID eventId, UUID transactionId, String accountId, BigDecimal amount, String currency,
        String counterpartyCountry, String reason, String decision, Double riskScore,
        Integer investigationDepth, String ruleVersion, String scoringVersion,
        String decisionPolicyVersion, String correlationId, Instant occurredAt) {

    public TransactionEvent adjudicated(String decision,String rationale,double riskScore,int depth,
                                        String scoringVersion,String policyVersion) {
        return new TransactionEvent(UUID.randomUUID(),transactionId,accountId,amount,currency,
                counterpartyCountry,rationale,decision,riskScore,depth,ruleVersion,
                scoringVersion,policyVersion,correlationId,Instant.now());
    }
}
