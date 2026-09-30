package com.tradesentry.agent.nodes;

import com.tradesentry.agent.client.CaseDataClient;
import com.tradesentry.agent.client.CaseDataClient.AccountHistory;
import com.tradesentry.agent.client.CaseDataClient.SimilarCase;
import com.tradesentry.agent.graph.InvestigationState;
import com.tradesentry.agent.graph.Node;
import com.tradesentry.agent.scoring.DecisionPolicy;
import com.tradesentry.agent.scoring.RiskScorer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Factory for the individual nodes that make up the investigation graph.
 *
 * <p>Nodes own investigation data gathering and state transitions. Scoring and
 * disposition are delegated to strategy interfaces so those policies can evolve
 * independently from the workflow.
 */
@Component
public class InvestigationNodes {

    private static final Logger log = LoggerFactory.getLogger(InvestigationNodes.class);

    private final CaseDataClient caseData;
    private final RiskScorer riskScorer;
    private final DecisionPolicy decisionPolicy;

    public InvestigationNodes(CaseDataClient caseData,
                              RiskScorer riskScorer,
                              DecisionPolicy decisionPolicy) {
        this.caseData = caseData;
        this.riskScorer = riskScorer;
        this.decisionPolicy = decisionPolicy;
    }

    public Node<InvestigationState> enrich() {
        return state -> {
            int lookbackDays = state.investigationDepth() == 0 ? 90 : 365;
            AccountHistory history = caseData.getAccountHistory(state.accountId(), lookbackDays);

            List<String> evidence = new ArrayList<>(state.evidence());
            evidence.add(String.format(
                    "account history (%dd): riskBand=%s, priorFlags=%d, priorSar=%s, maxAmount=%.2f",
                    lookbackDays, history.riskBand(), history.priorFlags(),
                    history.hasPriorSar(), history.maxAmount()));

            log.info("enrich [tx={}] lookback={} riskBand={} priorSar={} priorFlags={}",
                    state.transactionId(), lookbackDays, history.riskBand(),
                    history.hasPriorSar(), history.priorFlags());

            return state.withEnrichment(history.riskBand(), history.hasPriorSar())
                    .withEvidence(evidence);
        };
    }

    public Node<InvestigationState> retrieveCases() {
        return state -> {
            int maxResults = state.investigationDepth() == 0 ? 5 : 10;
            String summary = buildSummary(state);
            List<SimilarCase> cases = caseData.retrieveSimilarCases(
                    summary, state.amount(), state.counterpartyCountry(), maxResults);
            String worst = worstOutcome(cases);

            List<String> evidence = new ArrayList<>(state.evidence());
            evidence.add(String.format(
                    "similar historical cases: count=%d, worstOutcome=%s, requested=%d",
                    cases.size(), worst, maxResults));

            log.info("retrieveCases [tx={}] found={} requested={} worstOutcome={}",
                    state.transactionId(), cases.size(), maxResults, worst);

            return state.withSimilarCases(cases.size(), worst)
                    .withEvidence(evidence);
        };
    }

    public Node<InvestigationState> assess() {
        return state -> {
            double score = riskScorer.score(state);
            log.info("assess [tx={}] score={} depth={}",
                    state.transactionId(), score, state.investigationDepth());
            return state.withRiskScore(score);
        };
    }

    /**
     * A borderline result triggers a genuinely deeper read rather than merely
     * incrementing a counter. The next pass widens account-history lookback
     * and requests more similar cases.
     */
    public Node<InvestigationState> investigateDeeper() {
        return state -> {
            log.info("investigateDeeper [tx={}] depth {} -> {}",
                    state.transactionId(), state.investigationDepth(), state.investigationDepth() + 1);
            return state.deeper();
        };
    }

    public Node<InvestigationState> decide() {
        return state -> {
            String decision = decisionPolicy.decide(state);

            String rationale = String.format(
                    "decision=%s; score=%.2f; depth=%d; band=%s; priorSar=%s; similarCases=%d "
                            + "(worst=%s); flagReason=%s; evidence=%s",
                    decision, state.riskScore(), state.investigationDepth(),
                    state.accountRiskBand(), state.priorSar(), state.similarCaseCount(),
                    state.worstPriorOutcome(), state.flagReason(), state.evidence());

            log.info("decide [tx={}] -> {} ({})", state.transactionId(), decision, rationale);
            return state.withDecision(decision, rationale);
        };
    }

    private String buildSummary(InvestigationState state) {
        return String.format("amount=%s to %s; flagged for: %s",
                state.amount(), state.counterpartyCountry(), state.flagReason());
    }

    private String worstOutcome(List<SimilarCase> cases) {
        // Ignore weakly similar records when deriving the strongest historical signal.
        boolean sar = cases.stream()
                .anyMatch(c -> c.similarityScore() >= 0.70 && "SAR_FILED".equals(c.outcome()));
        if (sar) {
            return "SAR_FILED";
        }

        boolean escalated = cases.stream()
                .anyMatch(c -> c.similarityScore() >= 0.70 && "ESCALATED".equals(c.outcome()));
        if (escalated) {
            return "ESCALATED";
        }

        if (cases.isEmpty()) {
            return "NONE";
        }
        return "CLEARED";
    }
}
