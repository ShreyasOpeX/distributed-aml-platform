package com.tradesentry.agent.graph;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * A minimal, framework-free port of LangGraph's core execution model to typed Java.
 *
 * <p>A graph is a set of named {@link Node}s connected by edges. A single immutable state value
 * of type {@code S} is threaded through the nodes: each node receives the current state and
 * returns a new one. Execution starts at the entry point and follows edges until it reaches
 * {@link #END}.
 *
 * <p>Two kinds of edges exist:
 * <ul>
 *   <li><b>Fixed edges</b> ({@link #addEdge}) always route from one node to a fixed successor.</li>
 *   <li><b>Conditional edges</b> ({@link #addConditionalEdge}) compute the next node from the
 *       current state at runtime. These are what enable branching and cycles.</li>
 * </ul>
 *
 * <p>The graph validates its topology before execution and also enforces a step
 * budget. The topology validation catches configuration mistakes early; the
 * step budget protects the process from a non-terminating conditional cycle.
 *
 * @param <S> the type of state threaded through the graph
 */
public class StateGraph<S> {

    public static final String END = "__end__";

    private final Map<String, Node<S>> nodes = new HashMap<>();
    private final Map<String, String> edges = new HashMap<>();
    private final Map<String, Function<S, String>> conditionalEdges = new HashMap<>();
    private String entryPoint;
    private final int maxSteps;

    public StateGraph() {
        this(50);
    }

    public StateGraph(int maxSteps) {
        if (maxSteps <= 0) {
            throw new IllegalArgumentException("maxSteps must be positive");
        }
        this.maxSteps = maxSteps;
    }

    public StateGraph<S> addNode(String name, Node<S> node) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Node name must not be blank");
        }
        if (node == null) {
            throw new IllegalArgumentException("Node implementation must not be null");
        }
        if (nodes.containsKey(name)) {
            throw new IllegalArgumentException("Duplicate node: " + name);
        }
        nodes.put(name, node);
        return this;
    }

    public StateGraph<S> addEdge(String from, String to) {
        requireNodeName(from);
        requireTargetName(to);
        if (edges.containsKey(from)) {
            throw new IllegalArgumentException("Fixed edge already exists for '" + from + "'");
        }
        edges.put(from, to);
        return this;
    }

    public StateGraph<S> addConditionalEdge(String from, Function<S, String> router) {
        requireNodeName(from);
        if (router == null) {
            throw new IllegalArgumentException("Conditional router must not be null");
        }
        if (conditionalEdges.containsKey(from)) {
            throw new IllegalArgumentException("Conditional edge already exists for '" + from + "'");
        }
        conditionalEdges.put(from, router);
        return this;
    }

    public StateGraph<S> setEntryPoint(String name) {
        requireNodeName(name);
        this.entryPoint = name;
        return this;
    }

    public S invoke(S initialState) {
        validate();

        S state = initialState;
        String current = entryPoint;
        int steps = 0;

        while (!current.equals(END)) {
            if (steps++ >= maxSteps) {
                throw new IllegalStateException(
                        "Graph exceeded " + maxSteps + " steps — probable non-terminating cycle");
            }

            Node<S> node = nodes.get(current);
            state = node.apply(state);

            String next = nextNode(current, state);
            if (next == null || next.isBlank()) {
                throw new IllegalStateException(
                        "Router from '" + current + "' returned a blank destination");
            }
            current = next;
        }

        return state;
    }

    private void validate() {
        if (entryPoint == null) {
            throw new IllegalStateException("No entry point set");
        }
        if (!nodes.containsKey(entryPoint)) {
            throw new IllegalStateException("Entry point '" + entryPoint + "' is not registered");
        }

        for (Map.Entry<String, String> edge : edges.entrySet()) {
            validateTarget(edge.getKey(), edge.getValue());
        }

        for (String node : conditionalEdges.keySet()) {
            if (!nodes.containsKey(node)) {
                throw new IllegalStateException(
                        "Conditional edge source '" + node + "' is not registered");
            }
            if (edges.containsKey(node)) {
                throw new IllegalStateException(
                        "Node '" + node + "' cannot have both fixed and conditional edges");
            }
        }
    }

    private void validateTarget(String from, String target) {
        if (!nodes.containsKey(from)) {
            throw new IllegalStateException("Edge source '" + from + "' is not registered");
        }
        if (!END.equals(target) && !nodes.containsKey(target)) {
            throw new IllegalStateException(
                    "Edge from '" + from + "' targets unknown node '" + target + "'");
        }
    }

    private String nextNode(String current, S state) {
        Function<S, String> router = conditionalEdges.get(current);
        if (router != null) {
            return router.apply(state);
        }
        return edges.getOrDefault(current, END);
    }

    private void requireNodeName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Node name must not be blank");
        }
    }

    private void requireTargetName(String name) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Edge target must not be blank");
        }
    }
}
