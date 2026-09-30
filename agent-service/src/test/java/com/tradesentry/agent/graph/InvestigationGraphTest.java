package com.tradesentry.agent.graph;

import com.tradesentry.agent.client.CaseDataClient;
import com.tradesentry.agent.nodes.InvestigationNodes;
import com.tradesentry.agent.scoring.DeterministicRiskScorer;
import com.tradesentry.agent.scoring.ThresholdDecisionPolicy;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InvestigationGraphTest {

    /**
     * In-memory CaseDataClient whose responses are fixed by the constructor, so each test can
     * dial in exactly the signals it wants to exercise.
     */
    static final class FakeCaseDataClient implements CaseDataClient {
        private final String riskBand;
        private final boolean priorSar;
        private final List<SimilarCase> similarCases;

        FakeCaseDataClient(String riskBand, boolean priorSar, List<SimilarCase> similarCases) {
            this.riskBand = riskBand;
            this.priorSar = priorSar;
            this.similarCases = similarCases;
        }

        @Override
        public AccountHistory getAccountHistory(String accountId, int lookbackDays) {
            return new AccountHistory(accountId, 100, 2000.0, 50000.0, 1, priorSar, riskBand);
        }

        @Override
        public List<SimilarCase> retrieveSimilarCases(String summary, BigDecimal amount,
                                                      String counterpartyCountry, int maxResults) {
            return similarCases;
        }
    }

    private StateGraph<InvestigationState> graphFor(FakeCaseDataClient fake) {
        return new InvestigationGraphFactory().investigationGraph(new InvestigationNodes(fake, new DeterministicRiskScorer(), new ThresholdDecisionPolicy()));
    }

    @Test
    void strongSignalsEscalate() {
        FakeCaseDataClient fake = new FakeCaseDataClient("HIGH", true,
                List.of(new CaseDataClient.SimilarCase("case-1", 0.9, "SAR_FILED", "prior")));
        StateGraph<InvestigationState> graph = graphFor(fake);

        InvestigationState initial = InvestigationState.start(
                UUID.randomUUID(), "acc-strong", new BigDecimal("75000"), "KP",
                "high-risk counterparty country: KP; large amount over 50000");

        InvestigationState result = graph.invoke(initial);

        assertEquals("ESCALATE", result.decision());
        assertNotNull(result.rationale());
    }

    @Test
    void weakSignalsClear() {
        FakeCaseDataClient fake = new FakeCaseDataClient("LOW", false, List.of());
        StateGraph<InvestigationState> graph = graphFor(fake);

        InvestigationState initial = InvestigationState.start(
                UUID.randomUUID(), "acc-weak", new BigDecimal("51000"), "US",
                "large amount over 50000");

        InvestigationState result = graph.invoke(initial);

        assertEquals("CLEAR", result.decision());
    }

    @Test
    void borderlineCaseLoopsThenDecides() {
        FakeCaseDataClient fake = new FakeCaseDataClient("HIGH", false, List.of());
        StateGraph<InvestigationState> graph = graphFor(fake);

        InvestigationState initial = InvestigationState.start(
                UUID.randomUUID(), "acc-border", new BigDecimal("9500"), "US",
                "possible structuring (amount just under reporting threshold)");

        InvestigationState result = graph.invoke(initial);

        assertTrue(result.investigationDepth() >= 1, "expected the graph to loop at least once");
        assertTrue(result.evidence().size() >= 4, "deeper pass should add enrichment and case evidence");
        assertNotNull(result.rationale());
        assertTrue(result.rationale().contains("evidence="));
    }
}
