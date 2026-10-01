# Architecture

This document describes how TradeSentry is structured, how a transaction moves
through the system, and the key design decisions behind the messaging and
service boundaries.

## Contents

- [Design principles](#design-principles)
- [Service topology](#service-topology)
- [End-to-end event flow](#end-to-end-event-flow)
- [Kafka topics and consumer groups](#kafka-topics-and-consumer-groups)
- [The investigation graph](#the-investigation-graph)
- [gRPC case-data access](#grpc-case-data-access)
- [Transaction lifecycle](#transaction-lifecycle)
- [Key design decisions](#key-design-decisions)

## Design principles

- **Choreography over orchestration.** Services react to events on Kafka topics
  rather than being called by a central coordinator. Each stage owns its own
  logic and advances the transaction by publishing the next event.
- **Independently deployable services.** Each service owns its data and its copy
  of the event contract. Services agree on the JSON/protobuf wire format, not on
  a shared domain jar.
- **Right transport for the job.** Kafka handles asynchronous, ordered,
  fan-out messaging between pipeline stages. gRPC handles synchronous
  request/response lookups within the investigation stage.
- **Deterministic, testable core.** The screening rules, the investigation
  graph, and the synthetic case data are all deterministic, which keeps the
  pipeline reproducible and unit-testable end to end.

## Service topology

| Service             | Inbound                                   | Outbound                                        |
|---------------------|-------------------------------------------|-------------------------------------------------|
| `core-service`      | REST intake; `transactions.ingested`, `transactions.adjudicated` (Kafka) | `transactions.ingested`, `transactions.flagged` (Kafka); PostgreSQL |
| `agent-service`     | `transactions.flagged` (Kafka)            | `transactions.adjudicated` (Kafka); gRPC to `case-data-service` |
| `case-data-service` | gRPC (`GetAccountHistory`, `RetrieveSimilarCases`) | —                                    |

## End-to-end event flow

```mermaid
sequenceDiagram
    participant C as Client
    participant Core as core-service
    participant K as Kafka
    participant Agent as agent-service
    participant CD as case-data-service
    participant DB as PostgreSQL

    C->>Core: POST /api/transactions
    Core->>DB: save (status = SUBMITTED)
    Core->>K: publish transactions.ingested
    Core-->>C: 202 Accepted (transactionId)

    K->>Core: rule-screen consumes ingested
    alt no rules match
        Core->>DB: status = CLEARED_EARLY
    else rules match
        Core->>DB: status = FLAGGED
        Core->>K: publish transactions.flagged
    end
    K->>Core: audit consumes ingested (log)

    K->>Agent: investigation-agent consumes flagged
    Agent->>CD: GetAccountHistory (gRPC, unary)
    Agent->>CD: RetrieveSimilarCases (gRPC, streaming)
    Agent->>Agent: run investigation graph
    Agent->>K: publish transactions.adjudicated

    K->>Core: case-mgmt consumes adjudicated
    Core->>DB: status = ADJUDICATED
    alt decision = ESCALATE
        Core->>Core: file SAR case
    end
    K->>Core: audit consumes adjudicated (log)
```

## Kafka topics and consumer groups

All topics are keyed by `accountId`. Kafka guarantees ordering only within a
partition, and all records sharing a key land on the same partition, so keying
by account gives strict per-account ordering while still allowing different
accounts to be processed in parallel across partitions.

| Topic                       | Partitions | Produced by                     | Consumed by (group)                                          |
|-----------------------------|-----------:|---------------------------------|--------------------------------------------------------------|
| `transactions.ingested`     | 3          | core-service (intake)           | core-service (`rule-screen`), core-service (`audit`)         |
| `transactions.flagged`      | 3          | core-service (screening)        | agent-service (`investigation-agent`)                        |
| `transactions.adjudicated`  | 3          | agent-service (investigation)   | core-service (`case-mgmt`), core-service (`audit`)           |
| `transactions.dlq`          | 1          | (reserved for failed records)   | —                                                            |

**Fan-out.** Kafka delivers every record to each consumer group independently.
`rule-screen` and `audit` both subscribe to `transactions.ingested` in separate
groups, so both receive the full stream without competing for records. The same
applies to `case-mgmt` and `audit` on `transactions.adjudicated`.

## The investigation graph

The agent-service ports LangGraph's core execution model to typed Java. A
`StateGraph<S>` threads a single immutable state value through named nodes,
following edges until it reaches a terminal `END` marker. Two edge types exist:
**fixed edges** always route to a fixed successor, and **conditional edges**
compute the next node from the current state at runtime — which is what enables
both branching and cycles. A `maxSteps` cap guarantees termination even if a
router produces a non-terminating cycle.

The investigation graph is mostly linear with one bounded loop:

```mermaid
flowchart TD
    start([start]) --> enrich
    enrich --> retrieveCases
    retrieveCases --> assess
    assess -->|borderline and depth < 2| investigateDeeper
    assess -->|otherwise| decide
    investigateDeeper --> enrich
    decide --> done([END])
```

| Node                | Responsibility                                                                 |
|---------------------|--------------------------------------------------------------------------------|
| `enrich`            | Fetch account history (risk band, prior SAR) via gRPC                          |
| `retrieveCases`     | Retrieve similar prior cases via gRPC; derive the worst prior outcome          |
| `assess`            | Compute a risk score from the accumulated signals                              |
| `investigateDeeper` | Increment investigation depth; the loop back edge triggers a fresh enrichment/retrieval pass |
| `decide`            | Map the final score to a decision and record a rationale                       |

**Risk scoring** (in `assess`) accumulates weighted signals: flag reason
(structuring, high-risk country, large amount), account risk band, prior SAR,
the worst outcome among similar cases, and a small increment per investigation
depth. The score is clamped to `[0, 1]`.

**Decision thresholds** (in `decide`):

| Score        | Decision   |
|--------------|------------|
| `>= 0.70`    | `ESCALATE` |
| `>= 0.40`    | `FLAG`     |
| `< 0.40`     | `CLEAR`    |

A case is **borderline** when the score is in `[0.40, 0.70)`. Borderline cases
loop back through `investigateDeeper` — up to `MAX_INVESTIGATION_DEPTH` (2)
times — before a final decision, giving the extra depth a chance to push the
score across a threshold.

The `assess` node delegates to the `RiskScorer` strategy. The current implementation is a deterministic,
auditable scorer; a calibrated ML model or governed model-based scorer can replace that strategy
without changing the graph. Likewise, `DecisionPolicy` isolates disposition thresholds from workflow control flow.

Deeper investigation is intentionally bounded. The first pass requests up to 5 similar cases over a 90-day
lookback; deeper passes request up to 10 cases over a 365-day lookback. The case-data service enforces those
request bounds rather than silently returning the first-pass amount of evidence.

## gRPC case-data access

During investigation, the agent reads account history and prior cases from
`case-data-service` over gRPC. The proto contract exercises two RPC styles:

- `GetAccountHistory` — **unary** (one request, one response).
- `RetrieveSimilarCases` — **server-streaming** (one request, a stream of
  `SimilarCase` responses drained by the client into a list).

The investigation nodes depend on the `CaseDataClient` interface, not on gRPC
stubs directly. This keeps the graph fully testable with an in-memory fake and
lets the gRPC-backed implementation (`GrpcCaseDataClient`) be swapped in without
touching any node or graph wiring.

The case data returned today is synthetic and deterministic, derived from the
`accountId` hash, so investigations are reproducible.

## Transaction lifecycle

```mermaid
stateDiagram-v2
    [*] --> SUBMITTED: intake
    SUBMITTED --> CLEARED_EARLY: no rules match
    SUBMITTED --> FLAGGED: rules match
    FLAGGED --> ADJUDICATED: investigation complete
    CLEARED_EARLY --> [*]
    ADJUDICATED --> [*]
```

| Status          | Meaning                                                       |
|-----------------|---------------------------------------------------------------|
| `SUBMITTED`     | Received via the intake API; screening has not yet run        |
| `CLEARED_EARLY` | Screening found nothing suspicious; no investigation needed   |
| `FLAGGED`       | Screening flagged the transaction for agent investigation     |
| `ADJUDICATED`   | Investigation finished and a final disposition was recorded   |

## Key design decisions

- **Per-account ordering via message keys.** Keying every topic by `accountId`
  preserves the ordering of a single account's events while allowing parallelism
  across accounts.
- **Duplicated event records per service.** `core-service` and `agent-service`
  each define their own `TransactionEvent` record with an identical shape.
  Consumers ignore the producer's `__TypeId__` header
  (`spring.json.use.type.headers: false`) and bind to their own default type,
  so services stay decoupled at the code level while agreeing on the JSON
  contract.
- **Kafka within a service, gRPC across the investigation boundary.** Async
  events decouple pipeline stages and absorb load; a synchronous gRPC lookup is
  the natural fit for the read-side account/case data the agent needs inline.
- **Bounded graph execution.** The `maxSteps` cap and `MAX_INVESTIGATION_DEPTH`
  ensure the investigation always terminates.


## Production-hardening flow

The reference implementation now hardens the pipeline around four durable boundaries:

1. Intake durability: transaction row and ingested outbox row commit together.
2. Consumer idempotency: each side-effecting consumer records (eventId, consumerName) in an inbox table.
3. Investigation durability: the agent persists completed investigation state, including evidence and policy versions.
4. Case durability: case management persists the adjudication, investigation case and SAR workflow record.

The resulting reliability model is at-least-once delivery with idempotent side effects.

### Outbox relay

Multiple core instances can run the outbox publisher. Pending rows are selected with PostgreSQL FOR UPDATE SKIP LOCKED, so relay instances divide work without claiming the same row concurrently.

A relay crash after Kafka acknowledgement can still cause a duplicate. That is why the inbox remains necessary.

### Retry and DLQ

Kafka listeners use bounded retries and publish unrecoverable records to transactions.dlq. The DLQ is an operational recovery boundary, not a discard queue.

### gRPC failure budget

Case-data calls use explicit deadlines. The investigation therefore has a finite dependency budget. A timeout fails the Kafka processing attempt, allowing the listener retry/DLQ policy to take over.

### Security and admission control

The REST boundary uses role-based Spring Security. Transaction submission requires AML_OPERATOR; transaction reads require AML_ANALYST or AML_OPERATOR.

A per-instance requests-per-second limiter returns 429 during bursts. Cluster-wide quotas belong at a shared gateway/Redis layer.

### Observability

Actuator exposes health/metrics and Micrometer/OpenTelemetry is configured for tracing. The transaction event carries a correlation ID so the asynchronous pipeline can preserve request lineage.

### Versioned decisions

The event contract carries ruleVersion, scoringVersion, and decisionPolicyVersion. These are persisted with investigations so an historical decision can be reconstructed after policy changes.

For the complete operational model see docs/PRODUCTION-HARDENING.md, docs/FAILURE-MODES.md, and docs/adr/.
