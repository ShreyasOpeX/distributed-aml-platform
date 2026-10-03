# TradeSentry

Event-driven Anti-Money-Laundering (AML) transaction monitoring platform with a
LangGraph-style investigation agent.

TradeSentry ingests transactions over a REST API, screens them against
deterministic AML rules, and routes anything suspicious to an autonomous
investigation agent. The agent enriches each case with account history and prior
similar cases (fetched over gRPC), scores the risk, and records a final
disposition. Every stage communicates asynchronously through Apache Kafka.

## Contents

- [Overview](#overview)
- [Architecture](#architecture)
- [Technology stack](#technology-stack)
- [Modules](#modules)
- [Quick start](#quick-start)
- [Documentation](#documentation)
- [Project status](#project-status)

## Overview

The current reference implementation is an event-driven AML pipeline. The README documents this implementation workflow; the production-scale evolution is documented separately in `docs/SCALING.md`.

Current workflow:

1. **REST intake** — authenticate, authorize, rate-limit, attach correlation ID, and persist the transaction.
2. **Transactional outbox** — commit the transaction and Kafka publication intent atomically in PostgreSQL.
3. **Kafka ingestion** — publish `transactions.ingested` through the outbox relay.
4. **Deterministic screening** — clear safe transactions early or publish `transactions.flagged`.
5. **Investigation** — consume the flagged event idempotently, enrich through gRPC, retrieve similar cases, run the bounded StateGraph, score risk, and apply the decision policy.
6. **Adjudication** — publish `transactions.adjudicated` with the decision, evidence, rationale, and policy versions.
7. **Case management** — persist the decision and create an investigation case; escalations create a SAR workflow case.
8. **Audit** — independently consume lifecycle events and persist durable audit records.

Kafka is used for asynchronous stage-to-stage communication. gRPC is used for synchronous case-data reads inside the investigation stage.

The current reference implementation intentionally uses deterministic/synthetic screening, case data, and scoring. It does not claim global exactly-once processing or actual regulatory SAR filing.\n\n## Architecture

TradeSentry is a choreographed, event-driven AML transaction-monitoring platform. PostgreSQL owns durable business state, Kafka owns asynchronous stage-to-stage transport, agent-service owns the bounded investigation workflow, and gRPC provides synchronous case-data reads required during an investigation.

### 1. Complete architecture

~~~text
Client
  |
  | POST /api/transactions
  v
+-------------------------------------------------------------------+
| core-service                                                      |
|                                                                   |
| Security/AuthZ -> Rate limiting -> Correlation ID                 |
|                         |                                         |
|                         v                                         |
|                  TransactionService                               |
|                         |                                         |
|              ONE PostgreSQL transaction                           |
|                    /             \                                 |
|                   v               v                                |
|            transactions      outbox_events                         |
|                                    |                              |
|                              Outbox relay                          |
+------------------------------------|------------------------------+
                                     |
                                     v
                              Apache Kafka
                                     |
       +-----------------------------+-----------------------------+
       |                             |                             |
       v                             v                             v
transactions.ingested        transactions.flagged       transactions.adjudicated
       |                             |                             |
       v                             v                             v
 rule-screen group             agent-service group       case-mgmt group
       |                             |                             |
       |                             v                             |
       |                      Idempotency inbox                    |
       |                             |                             |
       |                       StateGraph agent                    |
       |                             |                             |
       |                         gRPC case data                    |
       |                             |                             |
       |                      investigation_states                 |
       |                             |                             |
       +-----------------------------+-----------------------------+
                                     |
                               Case management
                                     |
                           +---------+---------+
                           |                   |
                           v                   v
                  investigation_cases      sar_cases
                           |
                           v
                    transaction decision

Audit is an independent consumer group of the pipeline topics and
writes durable audit_records.

case-data-service
  |
  +-- GetAccountHistory()       unary gRPC
  +-- RetrieveSimilarCases()    server-streaming gRPC
~~~

The important boundaries are:

- REST: external API boundary.
- PostgreSQL: durable transactional state.
- Transactional outbox: database-to-Kafka reliability boundary.
- Kafka: asynchronous pipeline transport and buffering.
- Consumer inbox: idempotency boundary.
- StateGraph: bounded investigation workflow.
- gRPC: synchronous case-data dependency inside investigation.
- Case/SAR persistence: durable adjudication state.
- Audit records: durable event history.
- Security/rate limiting: protection at the synchronous API boundary.
- Metrics/tracing/correlation IDs: operational visibility.

### 2. End-to-end transaction lifecycle

#### Step 1 — API admission

The client submits a transaction through POST /api/transactions.

The API boundary applies authentication, role-based authorization, correlation-ID creation/propagation, and bounded rate limiting.

The transaction ID is returned after the database transaction succeeds.

#### Step 2 — Atomic transaction + outbox commit

TransactionService creates the business transaction and the corresponding ingested event in the same PostgreSQL transaction.

The outbox row contains:

- event ID;
- Kafka topic;
- Kafka key;
- serialized event payload;
- creation time;
- publication status.

This closes the classic database-to-Kafka failure window.

If PostgreSQL rolls back, neither the business transaction nor its Kafka intent exists.

If PostgreSQL commits while Kafka is unavailable, the outbox row remains durable and pending.

#### Step 3 — Outbox relay

The scheduled relay publishes pending outbox rows to Kafka.

Multiple core-service instances can run the relay. Pending rows are selected using PostgreSQL FOR UPDATE SKIP LOCKED so relay instances divide the work instead of concurrently claiming the same rows.

The row is marked PUBLISHED only after Kafka acknowledges the send.

The relay is intentionally at-least-once. If Kafka accepts a record and the relay crashes before marking the database row published, the record can be sent again.

That is why consumer idempotency is required.

### 3. Kafka topology

| Topic | Producer | Consumer groups | Key | Purpose |
|---|---|---|---|---|
| transactions.ingested | core-service outbox | rule-screen, audit | accountId | accepted transaction |
| transactions.flagged | screening outbox | investigation-agent, audit | accountId | transaction requiring investigation |
| transactions.adjudicated | investigation agent | case-mgmt, audit | accountId | final investigation disposition |
| transactions.dlq | Kafka error handlers | operational recovery | partition-based | failed records after bounded retries |

The reference configuration uses three partitions for the main pipeline topics and one partition for the DLQ.

Kafka ordering is only guaranteed inside a partition. There is no global ordering guarantee across partitions or across different topics.

accountId is used as the Kafka key so one account remains ordered within a topic while different accounts can execute concurrently.

### 4. Screening

The rule-screen consumer receives transactions.ingested.

Before processing, it atomically claims eventId + consumer name in the PostgreSQL inbox. A redelivery therefore becomes a no-op.

Current screening rules are deterministic and project-specific:

- possible structuring in the configured near-threshold amount range;
- large amount detection;
- configured high-risk counterparty countries.

These are illustrative application rules, not universal AML regulatory thresholds.

No matching reasons produce CLEARED_EARLY.

Matching reasons produce FLAGGED and an outbox-backed transactions.flagged event.

The event carries ruleVersion, currently rules-v1.

### 5. Investigation agent

agent-service consumes transactions.flagged and claims the event through its inbox before invoking the StateGraph.

The workflow is:

~~~text
start
  |
  v
enrich
  |
  v
retrieveCases
  |
  v
assess
  |
  +---- not borderline ----> decide ----> END
  |
  +---- borderline --------> investigateDeeper
                                  |
                                  +----> enrich
~~~

The loop is bounded by the business investigation-depth limit.

StateGraph also has its own maxSteps execution guard. These are different safeguards:

- business depth prevents unlimited investigation;
- maxSteps prevents an accidental graph cycle from running forever.

### 6. Enrichment and case retrieval

The enrich node calls case-data-service GetAccountHistory over gRPC.

The initial pass uses a shorter lookback. Deeper investigation expands the lookback.

Account history includes:

- transaction count;
- average amount;
- maximum amount;
- prior flags;
- prior SAR indicator;
- account risk band.

The retrieveCases node calls RetrieveSimilarCases using gRPC server streaming.

The current case-data implementation is deterministic and synthetic. CaseDataClient isolates the graph from the transport and data source so a real case store/search implementation can replace it later.

### 7. Risk scoring

The assess node delegates to the RiskScorer strategy.

The current implementation is DeterministicRiskScorer. It combines deterministic signals including:

- structuring-related reason;
- high-risk counterparty country;
- transaction amount;
- account risk band;
- prior SAR history;
- similar-case outcomes;
- investigation depth.

The score is deterministic and bounded.

TradeSentry does not currently use an LLM for risk scoring. The strategy boundary exists so a future governed model-based scorer can be introduced without rewriting the StateGraph.

### 8. Decision policy

The decide node delegates to DecisionPolicy.

The current ThresholdDecisionPolicy uses:

| Risk score | Decision |
|---:|---|
| < 0.40 | CLEAR |
| 0.40 to < 0.70 | FLAG |
| >= 0.70 | ESCALATE |

The borderline interval can trigger deeper investigation before the final disposition.

These thresholds are application policy, not claims about regulatory AML thresholds.

### 9. Explainability and investigation persistence

The final InvestigationState contains:

- transaction/account identity;
- amount and counterparty country;
- flag reason;
- account risk information;
- prior SAR information;
- similar-case outcome information;
- risk score;
- investigation depth;
- evidence;
- decision;
- rationale.

After completion, the agent persists the completed investigation in investigation_states.

The stored state includes evidence, rationale, score, depth, and scoring/policy versions.

This makes the investigation durable and independently auditable instead of leaving the reasoning only in memory or logs.

### 10. Adjudication event

The agent publishes transactions.adjudicated.

The event carries:

- a new eventId;
- transactionId;
- accountId;
- transaction data;
- decision;
- rationale;
- risk score;
- investigation depth;
- ruleVersion;
- scoringVersion;
- decisionPolicyVersion;
- correlationId;
- occurrence time.

eventId identifies the individual event.

transactionId identifies the business transaction.

Keeping these identities separate is important for replay and idempotency.

### 11. Case management and SAR workflow

The case-mgmt consumer claims the adjudication event idempotently.

It persists the final decision, risk score and rationale on the transaction.

It also creates an investigation_cases record containing the durable investigation/case context and policy versions.

If the decision is ESCALATE, it creates a sar_cases record.

The SAR entity represents opening the SAR workflow. Actual submission to a regulatory filing system is intentionally outside this project's boundary.

### 12. Durable audit

Audit is a separate Kafka consumer group.

Therefore the same event can independently reach business processing and auditing:

~~~text
transactions.ingested
       |
       +---- rule-screen group
       |
       +---- audit group

transactions.flagged
       |
       +---- investigation-agent group
       |
       +---- audit group

transactions.adjudicated
       |
       +---- case-mgmt group
       |
       +---- audit group
~~~

The audit consumer persists durable audit_records.

The audit record contains event identity, transaction/account identity, event type, payload and occurrence time.

Application logs remain operational diagnostics. They are not the durable audit source of truth.

### 13. Idempotency model

TradeSentry deliberately uses at-least-once delivery with idempotent side effects.

Each side-effecting consumer records:

~~~text
eventId
consumerName
processedAt
~~~

The database key is the pair eventId + consumerName.

The claim is performed atomically with PostgreSQL conflict handling.

Therefore:

~~~text
Kafka delivery #1
       |
       v
inbox insert succeeds
       |
       v
business side effect

Kafka delivery #2
       |
       v
inbox conflict
       |
       v
no-op
~~~

This protects the system from Kafka redelivery, consumer crashes, manual replay, and duplicate outbox publication.

The system intentionally does not claim global exactly-once semantics.

### 14. Retry and DLQ

Kafka listener failures use bounded retries with backoff.

After retries are exhausted, the failed record is routed to transactions.dlq.

The intended operational workflow is:

~~~text
failure
  |
  v
bounded retry
  |
  v
retry exhausted
  |
  v
DLQ
  |
  v
alert / inspect
  |
  v
fix root cause
  |
  v
controlled replay
~~~

The DLQ is a recovery boundary, not a discard queue.

### 15. gRPC reliability

The agent uses explicit configurable deadlines for case-data RPCs.

A dependency that becomes slow or unavailable therefore cannot block a Kafka consumer indefinitely.

The failure path is:

~~~text
agent -> gRPC
          |
          +-- deadline exceeded
                    |
                    v
             listener failure
                    |
                    v
             Kafka retry
                    |
                    v
                  DLQ
~~~

Production extensions can add selective retries for transient gRPC statuses, circuit breaking, bulkheads, and an overall investigation latency budget.

### 16. Security

The reference API uses Spring Security role-based authorization.

Current roles are:

- AML_OPERATOR: transaction submission and authorized operational access.
- AML_ANALYST: transaction/investigation reads.

Credentials are supplied through environment variables.

HTTP Basic is used as the reference authentication mechanism. Production deployment should use an enterprise OIDC/OAuth2 identity provider and map identity claims to application roles.

### 17. Rate limiting and backpressure

The REST boundary has a bounded per-instance requests-per-second limiter.

When the limit is exceeded, the API returns HTTP 429 with Retry-After.

This protects an individual application instance from an uncontrolled burst.

It is not a claim of cluster-wide rate limiting.

For horizontally scaled production deployment, global quotas should be enforced at an API gateway or shared Redis-backed layer.

Kafka provides durable buffering between asynchronous stages. Consumer concurrency, gRPC deadlines and bounded admission prevent uncontrolled synchronous work from exhausting process resources.

### 18. Observability

The API accepts or generates X-Correlation-Id.

The correlation ID is included in the event contract so request lineage can cross asynchronous boundaries.

The services expose operational instrumentation through:

- Spring Boot Actuator;
- health endpoints;
- Micrometer metrics;
- Prometheus registry;
- OpenTelemetry tracing configuration.

Important production metrics include:

- API throughput;
- screening throughput;
- screening flag rate;
- Kafka consumer lag;
- outbox backlog;
- investigation latency;
- gRPC latency and errors;
- retry count;
- DLQ depth;
- decision distribution;
- investigation depth distribution;
- case/SAR creation rate.

### 19. Horizontal scaling

Kafka partitions are the primary unit of consumer parallelism.

For a consumer group:

~~~text
Kafka partitions
      |
      +---- service instance A
      +---- service instance B
      +---- service instance C
~~~

A partition is assigned to at most one consumer in a consumer group at a time.

Therefore:

- increasing replicas can increase throughput until partition capacity becomes the limit;
- consumer concurrency above useful partition count does not create additional Kafka parallelism;
- different consumer groups process the same event stream independently;
- accountId keying preserves per-account ordering within each topic.

A highly active account can create a hot partition. That is a deliberate trade-off for per-account ordering.

### 20. Data ownership

| Data | Owner | Reason |
|---|---|---|
| Transaction business state | core-service PostgreSQL | core owns transaction lifecycle |
| Outbox events | core-service PostgreSQL | atomic with transaction state |
| Screening policy | core-service | screening belongs to intake |
| Investigation workflow state | agent-service PostgreSQL | agent owns investigation execution |
| Investigation cases | core-service PostgreSQL | case management owns disposition |
| SAR workflow records | core-service PostgreSQL | case management owns escalation |
| Audit records | core-service PostgreSQL | centralized durable audit trail |
| Account history/similar cases | case-data-service | isolated data-provider responsibility |

The case-data implementation is currently synthetic. A production implementation would connect the service to actual account/case data sources.

### 21. Failure-mode analysis

| Failure | Expected behavior | Recovery |
|---|---|---|
| PostgreSQL unavailable at intake | request fails | retry after DB recovery |
| Kafka unavailable after DB commit | outbox remains pending | relay retries |
| Relay crashes after Kafka send | duplicate possible | consumer idempotency |
| Screening consumer crashes | Kafka redelivery | inbox prevents duplicate effect |
| Agent gRPC timeout | processing attempt fails | retry, then DLQ |
| Agent crashes after persistence | Kafka may redeliver | inbox prevents duplicate processing |
| Case-management crash | adjudication remains in Kafka | consumer resumes |
| Audit consumer crash | event remains in Kafka | audit group resumes |
| DLQ growth | operational alert | inspect, fix, replay |
| API burst | HTTP 429 | client backoff/gateway quota |
| Hot account | partition bottleneck | revisit key/order trade-off |

### 22. Why Kafka and gRPC are both used

They solve different problems.

Kafka is used between independently progressing pipeline stages because it provides asynchronous processing, durable buffering, consumer isolation, replay, and independent scaling.

gRPC is used inside investigation because the graph needs immediate, typed, synchronous answers before it can choose its next state transition.

The architecture therefore avoids both extremes:

- turning the whole pipeline into tightly coupled synchronous HTTP calls;
- using Kafka as a substitute for synchronous request/response reads.

### 23. Why the investigation component is agentic

The investigation component is not an LLM chatbot.

It is a bounded stateful investigation agent implemented using a hand-rolled StateGraph.

Its agentic behavior comes from:

1. maintaining investigation state;
2. selecting the next transition from current state;
3. detecting borderline outcomes;
4. looping into deeper investigation;
5. retrieving additional evidence;
6. reassessing risk;
7. stopping at an explicit business bound;
8. producing an explainable disposition.

It is therefore architecturally similar to a LangGraph-style stateful workflow while remaining deterministic and framework-independent.

### 24. Reliability contract

~~~text
Client
  |
  v
Authenticated + rate-limited REST
  |
  v
PostgreSQL transaction + outbox
  |
  v
Kafka
  |
  v
Idempotent screening
  |
  +----> CLEAR
  |
  v
Kafka flagged event
  |
  v
Idempotent investigation
  |
  +---- bounded StateGraph
  +---- bounded gRPC
  +---- persistent state
  +---- versioned policies
  |
  v
Kafka adjudicated event
  |
  +----> transaction decision
  +----> investigation case
  +----> SAR case when escalated
  +----> durable audit
  |
  v
At-least-once delivery
+
Idempotent side effects
~~~

TradeSentry deliberately does not claim:

- global exactly-once processing;
- global ordering;
- universal AML regulatory thresholds;
- production regulatory filing capability;
- LLM-based risk decisions;
- cluster-wide rate limiting from the in-memory limiter.

These are explicit system boundaries, not hidden assumptions.

See docs/ARCHITECTURE.md for the lower-level design and docs/PRODUCTION-HARDENING.md, docs/FAILURE-MODES.md, and docs/adr/ for reliability, failure analysis, and architectural decisions.

## Technology stack

| Concern            | Choice                                  |
|--------------------|-----------------------------------------|
| Language / runtime | Java 21                                 |
| Framework          | Spring Boot 4.1                          |
| Messaging          | Apache Kafka (KRaft mode)               |
| Sync RPC           | gRPC via Spring gRPC 1.0.3              |
| Persistence        | PostgreSQL 16 + Spring Data JPA         |
| Build              | Maven (multi-module reactor)            |
| Packaging          | Docker, Docker Compose                   |

## Modules

| Module              | Responsibility                                                        | Ports        |
|---------------------|----------------------------------------------------------------------|--------------|
| `proto`             | Shared Protocol Buffers definitions and generated gRPC stubs         | —            |
| `core-service`      | Transaction intake, rule screening, case management, audit           | 8080 (HTTP)  |
| `agent-service`     | Investigation state-graph agent                                      | 8081 (HTTP)  |
| `case-data-service` | gRPC provider of account history and similar prior cases             | 9090 (gRPC)  |

## Quick start

Prerequisites: Docker and Docker Compose.

```bash
# Build all images and start the full stack
docker compose up --build
```

Submit a transaction once the services are healthy:

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"acc-1","amount":75000,"currency":"USD","counterpartyCountry":"KP"}'
```

Follow the transaction through the pipeline in the service logs:

```bash
docker compose logs -f core-service agent-service
```

For local (non-Docker) development, running individual services, and the test
suite, see [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md).

## Documentation

| Document                                         | Purpose                                                      |
|--------------------------------------------------|-------------------------------------------------------------|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)     | System design, event flow, Kafka topics, investigation graph |
| [docs/API.md](docs/API.md)                       | REST API and gRPC service contracts                         |
| [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md)       | Local setup, build, run, and test instructions              |
| [docs/CONFIGURATION.md](docs/CONFIGURATION.md)   | Environment variables and configuration reference           |

## Project status

Early development (`0.1.0-SNAPSHOT`). The end-to-end pipeline is functional:
intake, screening, gRPC-backed investigation, and case management all run.
Screening rules, case data, and risk scoring are deterministic and synthetic;
they are the intended seams for future rule engines and model-based scoring.


## Reliability and governance

The AML pipeline now includes a transactional outbox, idempotent consumer inboxes, persistent investigation state, durable investigation/SAR cases, an append-oriented audit trail, bounded Kafka retries with a DLQ, gRPC deadlines, versioned screening/scoring/decision policies, API authentication/authorization, bounded rate limiting, OpenTelemetry tracing and documented failure-mode analysis.

The target processing model is at-least-once delivery with idempotent side effects. The platform intentionally does not claim global exactly-once semantics across PostgreSQL, Kafka and remote gRPC calls.

See docs/PRODUCTION-HARDENING.md, docs/FAILURE-MODES.md, and docs/adr/ for the design rationale.
