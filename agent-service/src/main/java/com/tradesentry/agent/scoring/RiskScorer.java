package com.tradesentry.agent.scoring;

import com.tradesentry.agent.graph.InvestigationState;

/**
 * Strategy boundary for calculating investigation risk.
 *
 * <p>The graph only knows that a scorer turns the current state into a score.
 * This keeps the workflow independent from the scoring implementation and
 * allows deterministic rules, a calibrated ML model, or a governed LLM scorer
 * to be introduced without rewriting graph wiring.
 */
@FunctionalInterface
public interface RiskScorer {

    double score(InvestigationState state);
}
