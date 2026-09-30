package com.tradesentry.agent.scoring;

import com.tradesentry.agent.graph.InvestigationState;
import org.springframework.stereotype.Component;

/**
 * Current governed threshold policy.
 *
 * <p>Thresholds are kept outside the graph so workflow control flow and
 * business disposition policy can evolve independently.
 */
@Component
public class ThresholdDecisionPolicy implements DecisionPolicy {

    private static final double ESCALATE_THRESHOLD = 0.70;
    private static final double FLAG_THRESHOLD = 0.40;

    @Override
    public String decide(InvestigationState state) {
        if (state.riskScore() >= ESCALATE_THRESHOLD) {
            return "ESCALATE";
        }
        if (state.riskScore() >= FLAG_THRESHOLD) {
            return "FLAG";
        }
        return "CLEAR";
    }
}
