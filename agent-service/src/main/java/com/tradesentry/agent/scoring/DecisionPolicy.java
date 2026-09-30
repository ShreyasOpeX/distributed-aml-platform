package com.tradesentry.agent.scoring;

import com.tradesentry.agent.graph.InvestigationState;

/**
 * Strategy boundary for converting a risk score into a disposition.
 */
@FunctionalInterface
public interface DecisionPolicy {

    String decide(InvestigationState state);
}
