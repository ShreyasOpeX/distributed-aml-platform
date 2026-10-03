# TradeSentry Production Hardening

## Reliability pipeline

The hardened pipeline is:

Client -> authenticated REST -> PostgreSQL transaction + outbox -> Kafka -> idempotent screening -> outbox -> Kafka -> idempotent investigation -> gRPC with deadline -> persistent investigation state -> Kafka adjudication -> durable case/SAR + transaction decision + durable audit.

## Transactional outbox

The intake transaction and its Kafka intent are committed in one PostgreSQL transaction. A scheduled relay publishes pending rows.

The relay is intentionally at-least-once. A crash after Kafka acknowledgement and before marking the row published can create a duplicate. Downstream consumers use eventId plus consumer name as an inbox key.

This is safer than direct DB-then-Kafka publication and avoids pretending the two systems have a distributed transaction.

## Idempotent consumers

Every event has eventId. Side-effecting consumers atomically insert (eventId, consumerName) using PostgreSQL ON CONFLICT DO NOTHING. A duplicate delivery becomes a no-op.

The system therefore uses at-least-once delivery with idempotent side effects, not end-to-end exactly-once.

## Persistent investigation and cases

The agent persists completed investigation state, including evidence, score, decision, rationale, depth and policy versions.

Core persists investigation_cases and sar_cases. The transaction row also stores the final decision, risk score and rationale.

A SAR row represents opening the SAR workflow. Actual regulatory submission is a separate integration boundary.

## Durable audit

audit_records stores event identity, transaction/account identity, event type, payload and occurrence time. The audit consumer has its own Kafka group and therefore receives the complete stream independently.

Operational logs remain useful but are not the compliance audit source of truth.

## Retry and DLQ

Kafka listeners retry a bounded number of times and then route the failed record to transactions.dlq. DLQ growth should trigger an operational alert.

A production replay process should inspect the failure, fix the cause, and explicitly replay the event.

## gRPC reliability

Case-data calls have explicit deadlines. A slow dependency therefore fails the current attempt instead of blocking the Kafka consumer forever.

Production extensions are exponential retry for transient statuses, circuit breaking, bulkheads and a shared investigation latency budget.

## Versioning

Events carry ruleVersion, scoringVersion and decisionPolicyVersion. Current reference versions are rules-v1, score-v1 and policy-v1.

Persisting these values makes historical decisions reproducible after policies evolve.

## Observability

Actuator exposes health and metrics. Micrometer/OpenTelemetry tracing is configured. X-Correlation-Id is accepted or generated at the API boundary and propagated through the event contract.

Important metrics include ingestion rate, screening flag rate, Kafka lag, investigation latency, gRPC errors/latency, decision distribution, outbox backlog, retry count, DLQ depth and SAR creation rate.

## Authentication and authorization

The reference API uses Spring Security HTTP Basic with environment-supplied credentials.

POST /api/transactions requires AML_OPERATOR. GET /api/transactions/** requires AML_ANALYST or AML_OPERATOR.

For production, replace Basic authentication with OIDC/OAuth2 and map identity-provider claims to roles.

## Rate limiting and backpressure

The API has a bounded per-instance requests-per-second limiter and returns HTTP 429 with Retry-After.

The in-memory limiter is intentionally not described as a global cluster quota. A horizontally scaled deployment should enforce global quotas at an API gateway/shared Redis layer.

Kafka supplies durable buffering, while bounded consumer concurrency, gRPC deadlines and rate limits prevent uncontrolled work from exhausting process memory.

## Agent-side adjudication outbox

The current agent persists completed investigation state and then publishes the
adjudication event. This is not the same atomicity guarantee as the core
transactional outbox.

For production, add an agent-side outbox containing the adjudication event and
relay it after the investigation-state transaction commits. This closes the
database-to-Kafka atomicity gap at the investigation boundary.

## Horizontal scaling

Kafka partitions are the primary consumer parallelism unit. A partition is owned by at most one member of a consumer group.

Adding instances distributes partitions across instances. Increasing concurrency above available partitions does not create additional Kafka parallelism.

accountId is the message key, preserving per-account ordering within each topic while allowing different accounts to run concurrently.

## Horizontal scaling

| Failure | Result | Recovery |
| --- | --- | --- |
| DB unavailable at intake | request fails | retry after DB recovery |
| Kafka unavailable | outbox remains pending | relay retries |
| relay crashes after Kafka send | duplicate possible | consumer inbox |
| consumer crashes | Kafka redelivers | idempotent handler |
| gRPC timeout | investigation attempt fails | Kafka retry then DLQ |
| case management crash | adjudication remains in Kafka | consumer resumes |
| audit crash | audit event remains in Kafka | audit group resumes |
| DLQ growth | alert/operator action | diagnose and replay |
| hot account key | one partition becomes bottleneck | review ordering/key strategy |

See [SCALING.md](SCALING.md) for the detailed production-scale architecture
and [FAILURE-MODES.md](FAILURE-MODES.md) for failure analysis.
