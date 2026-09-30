package com.tradesentry.agent.graph;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Immutable state threaded through the investigation {@link StateGraph}.
 *
 * <p>Every transition returns a new state. The state also carries an explicit evidence trail so
 * the final triage decision can explain which signals were considered.
 */
public record InvestigationState(
        UUID transactionId,
        String accountId,
        BigDecimal amount,
        String counterpartyCountry,
        String flagReason,
        String accountRiskBand,
        boolean priorSar,
        int similarCaseCount,
        String worstPriorOutcome,
        double riskScore,
        int investigationDepth,
        List<String> evidence,
        String decision,
        String rationale) {

    public static InvestigationState start(UUID transactionId, String accountId, BigDecimal amount,
                                           String counterpartyCountry, String flagReason) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                null, false, 0, null, 0.0, 0, List.of(), null, null);
    }

    public InvestigationState withEnrichment(String accountRiskBand, boolean priorSar) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth, evidence, decision, rationale);
    }

    public InvestigationState withSimilarCases(int similarCaseCount, String worstPriorOutcome) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth, evidence, decision, rationale);
    }

    public InvestigationState withRiskScore(double riskScore) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth, evidence, decision, rationale);
    }

    public InvestigationState withEvidence(List<String> evidence) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth, List.copyOf(evidence), decision, rationale);
    }

    public InvestigationState deeper() {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth + 1, evidence, decision, rationale);
    }

    public InvestigationState withDecision(String decision, String rationale) {
        return new InvestigationState(
                transactionId, accountId, amount, counterpartyCountry, flagReason,
                accountRiskBand, priorSar, similarCaseCount, worstPriorOutcome, riskScore,
                investigationDepth, evidence, decision, rationale);
    }

    public boolean isBorderline() {
        return riskScore >= 0.4 && riskScore < 0.7;
    }
}
