package com.tradesentry.agent.graph;

import com.tradesentry.agent.nodes.InvestigationNodes;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Assembles the bounded investigation graph.
 *
 * <p>The graph is linear for the first pass, then can loop through a deeper enrichment/retrieval
 * pass when the score is borderline. The depth cap prevents unbounded investigation work.
 */
@Configuration
public class InvestigationGraphFactory {

    public static final int MAX_INVESTIGATION_DEPTH = 2;

    @Bean
    public StateGraph<InvestigationState> investigationGraph(InvestigationNodes nodes) {
        return new StateGraph<InvestigationState>()
                .addNode("enrich", nodes.enrich())
                .addNode("retrieveCases", nodes.retrieveCases())
                .addNode("assess", nodes.assess())
                .addNode("investigateDeeper", nodes.investigateDeeper())
                .addNode("decide", nodes.decide())
                .setEntryPoint("enrich")
                .addEdge("enrich", "retrieveCases")
                .addEdge("retrieveCases", "assess")
                .addConditionalEdge("assess", state ->
                        state.isBorderline() && state.investigationDepth() < MAX_INVESTIGATION_DEPTH
                                ? "investigateDeeper" : "decide")
                .addEdge("investigateDeeper", "enrich")
                .addEdge("decide", StateGraph.END);
    }
}
