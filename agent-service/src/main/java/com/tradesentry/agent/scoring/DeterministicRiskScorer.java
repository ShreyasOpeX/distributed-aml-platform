package com.tradesentry.agent.scoring;

import com.tradesentry.agent.graph.InvestigationState;
import org.springframework.stereotype.Component;

/**
 * Auditable baseline scorer used by the current prototype.
 *
 * <p>Each contribution is explicit and bounded. The implementation deliberately
 * contains no I/O, so scoring remains deterministic and independently testable.
 */
@Component
public class DeterministicRiskScorer implements RiskScorer {

    private static final double STRUCTURING_WEIGHT = 0.30;
    private static final double HIGH_RISK_COUNTRY_WEIGHT = 0.30;
    private static final double LARGE_AMOUNT_WEIGHT = 0.20;
    private static final double HIGH_ACCOUNT_WEIGHT = 0.20;
    private static final double PRIOR_SAR_WEIGHT = 0.25;
    private static final double PRIOR_SAR_FILED_WEIGHT = 0.20;
    private static final double PRIOR_ESCALATED_WEIGHT = 0.10;
    private static final double DEPTH_WEIGHT = 0.08;

    @Override
    public double score(InvestigationState state) {
        double score = 0.0;

        String reason = state.flagReason();
        if (reason != null) {
            if (reason.contains("structuring")) {
                score += STRUCTURING_WEIGHT;
            }
            if (reason.contains("high-risk")) {
                score += HIGH_RISK_COUNTRY_WEIGHT;
            }
            if (reason.contains("large amount")) {
                score += LARGE_AMOUNT_WEIGHT;
            }
        }

        if ("HIGH".equalsIgnoreCase(state.accountRiskBand())) {
            score += HIGH_ACCOUNT_WEIGHT;
        }
        if (state.priorSar()) {
            score += PRIOR_SAR_WEIGHT;
        }
        if ("SAR_FILED".equals(state.worstPriorOutcome())) {
            score += PRIOR_SAR_FILED_WEIGHT;
        } else if ("ESCALATED".equals(state.worstPriorOutcome())) {
            score += PRIOR_ESCALATED_WEIGHT;
        }

        score += state.investigationDepth() * DEPTH_WEIGHT;
        return Math.min(1.0, score);
    }
}
