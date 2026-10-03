# TradeSentry — Scaled Architecture

This document describes how the current TradeSentry reference implementation can evolve into a production-scale AML transaction-monitoring platform.

The goal is not to replace the current workflow. The scaled design keeps the same business pipeline and strengthens its capacity, availability, isolation, and operational controls.

## 1. Current workflow vs scaled workflow

### Current reference workflow

~~~text
REST
  |
  v
core-service
  |
  +--> PostgreSQL transaction + outbox
  |
  v
Kafka: transactions.ingested
  |
  v
rule screening
  |
  +--> CLEARED_EARLY
  |
  v
Kafka: transactions.flagged
  |
  v
agent-service
  |
  +--> gRPC case-data
  +--> bounded StateGraph
  |
  v
Kafka: transactions.adjudicated
  |
  +--> case management
  +--> audit
~~~

### Scaled production workflow

~~~text
                    +----------------------+
                    | API Gateway / WAF     |
                    | Auth + Global Limits |
                    +----------+-----------+
                               |
                     +---------v---------+
                     | Core Service Pool |
                     |  N instances      |
                     +---------+---------+
                               |
                    +----------v-----------+
                    | PostgreSQL HA Cluster |
                    | Primary + Replicas    |
                    +----------+------------+
                               |
                       Transactional Outbox
                               |
                    +----------v-----------+
                    | Outbox Relay Pool     |
                    | N workers             |
                    +----------+-----------+
                               |
                    +----------v-----------+
                    | Kafka Cluster         |
                    | Multiple brokers      |
                    | Partitioned topics    |
                    +----+------+-----------+
                         |      |
              +----------+      +----------------+
              |                                 |
      +-------v--------+                +-------v--------+
      | Screening Pool |                | Audit Consumers|
      | N consumers    |                | independent    |
      +-------+--------+                +----------------+
              |
      transactions.flagged
              |
      +-------v----------------+
      | Investigation Workers  |
      | bounded concurrency    |
      +-------+----------------+
              |
        +-----+------+
        |            |
        v            v
   Agent DB       gRPC pool
        |            |
        |      +-----v-------------+
        |      | case-data service |
        |      | N instances       |
        |      +-------------------+
        |
        v
 transactions.adjudicated
        |
   +----+----------------+
   |                     |
   v                     v
Case management      Audit pipeline
   |                     |
   v                     v
Cases / SARs        Audit storage
~~~

The business sequence remains the same. Scaling changes how much work can execute concurrently and how failures are isolated.

---

## 2. Scaling principles

TradeSentry should scale around these boundaries:

1. Kafka partitions for asynchronous parallelism.
2. Stateless service replicas for request and consumer capacity.
3. Database read replicas for read-heavy workloads.
4. Bounded worker pools for investigation concurrency.
5. Independent consumer groups for workload isolation.
6. Shared rate limits for cluster-wide admission control.
7. Durable outboxes for database-to-event reliability.
8. Observability-driven capacity planning.

The most important rule is:

> Replicas do not automatically create throughput. The limiting resource must be scaled.

---

## 3. Kafka partition scaling

Kafka partitions are the primary unit of consumer parallelism.

If a topic has three partitions:

~~~text
P0 ---> Consumer A
P1 ---> Consumer B
P2 ---> Consumer C
~~~

Adding a fourth consumer without adding partitions does not create another active partition consumer for that group.

### Example

For a target of 12 active screening consumers:

~~~text
transactions.ingested
       |
       +--> P0
       +--> P1
       +--> ...
       +--> P11
~~~

The exact partition count should be derived from throughput, latency, recovery requirements, broker capacity, and expected growth rather than chosen arbitrarily.

### Important trade-off

Increasing partitions provides more potential parallelism but also increases:

- broker metadata
- replication traffic
- consumer coordination
- operational complexity
- partition reassignment work

Partition count should therefore be treated as a capacity-planning decision.

---

## 4. Account-key scaling and hot partitions

TradeSentry uses accountId as the Kafka key because per-account ordering is valuable.

That creates an intentional trade-off:

~~~text
accountId key
     |
     +--> same account -> same partition
     |
     +--> preserves account ordering
     |
     +--> highly active account -> hot partition risk
~~~

A single account cannot be arbitrarily spread across partitions without weakening its ordering guarantee.

### Production options

If hot accounts become a measurable bottleneck:

1. Keep strict per-account ordering and accept the hot partition.
2. Split processing into ordered and unordered workloads.
3. Partition by a composite key only where business semantics permit it.
4. Serialize only the operations that actually require account ordering.
5. Introduce account-level sequencing rather than relying entirely on Kafka partition ordering.

Do not change the key strategy simply to increase throughput if doing so breaks a business ordering requirement.

---

## 5. Consumer scaling

Each asynchronous stage can scale independently.

~~~text
Kafka topic
    |
    +--> consumer-1
    +--> consumer-2
    +--> consumer-3
    +--> ...
~~~

Screening, investigation, case management, and audit have different resource profiles.

For example:

- screening is CPU/rule evaluation heavy;
- investigation is latency heavy because of gRPC and graph execution;
- case management is database-write heavy;
- audit is persistence throughput heavy.

Independent consumer groups prevent one workload from consuming another workload's Kafka capacity.

---

## 6. Investigation worker pool

The investigation stage is the most important scaling boundary because it combines Kafka consumption, synchronous gRPC, database access, and bounded graph execution.

The current reference implementation uses blocking gRPC.

At scale, avoid allowing every Kafka listener thread to create unbounded synchronous work.

Use a bounded worker pool:

~~~text
Kafka consumers
      |
      v
bounded investigation queue
      |
      +--> worker 1 -> gRPC -> case-data
      +--> worker 2 -> gRPC -> case-data
      +--> worker 3 -> gRPC -> case-data
      +--> ...
~~~

The worker count should be bounded by:

- CPU
- database connection pool
- gRPC connection capacity
- downstream case-data capacity
- acceptable investigation latency
- Kafka poll timing

The goal is controlled concurrency, not maximum concurrency.

---

## 7. gRPC scaling

case-data-service should be horizontally scalable.

~~~text
                    +--> case-data-1
agent workers ------+--> case-data-2
                    +--> case-data-3
                    +--> case-data-N
~~~

A load balancer or service-discovery layer distributes RPCs.

### Connection considerations

At scale:

- reuse gRPC channels;
- avoid creating a channel per request;
- configure deadlines;
- bound concurrent RPCs;
- monitor latency and error rate;
- distinguish transient from permanent failures.

### Backpressure

If case-data-service becomes slow, increasing agent concurrency can make the problem worse.

The safer sequence is:

~~~text
gRPC latency increases
        |
        v
limit investigation concurrency
        |
        v
Kafka absorbs backlog
        |
        v
recover downstream capacity
~~~

This prevents a slow dependency from cascading into database and JVM exhaustion.

---

## 8. PostgreSQL scaling

PostgreSQL remains the source of truth for durable business state.

A production deployment can use:

~~~text
                  PostgreSQL primary
                         |
             +-----------+-----------+
             |                       |
             v                       v
       read replica 1          read replica 2
~~~

### Writes

Keep authoritative writes on the primary.

Important write-heavy tables include:

- transactions
- outbox_events
- processed_events
- investigation_states
- investigation_cases
- sar_cases
- audit_records

### Reads

Read replicas can serve suitable read-only workloads such as:

- historical investigation queries
- operational dashboards
- reporting
- analyst search

Do not send a read to a replica when the workflow requires read-after-write consistency from the primary.

---

## 9. Database connection pools

Horizontal scaling can accidentally overload PostgreSQL.

Example:

~~~text
20 service instances
x
30 DB connections each
=
600 potential connections
~~~

The database may become the bottleneck before application CPU does.

Connection-pool sizing must therefore be planned at the cluster level, not per instance in isolation.

Use:

- bounded Hikari pools;
- workload-specific connection budgets;
- database monitoring;
- connection utilization alerts;
- query latency tracking.

---

## 10. Transactional outbox scaling

Multiple outbox relay workers can safely divide pending rows using:

~~~text
SELECT ...
FOR UPDATE SKIP LOCKED
~~~

This allows:

~~~text
outbox table
    |
    +--> relay-1
    +--> relay-2
    +--> relay-3
    +--> relay-N
            |
            v
          Kafka
~~~

The relay should remain at-least-once.

Scaling relay workers increases publication throughput but does not eliminate duplicate publication risk.

Consumers must remain idempotent.

### Outbox bottlenecks

Monitor:

- pending row count
- oldest pending event age
- publication latency
- publication error rate
- relay throughput

A growing outbox backlog means Kafka publication capacity is below database event-generation rate.

---

## 11. Agent-side transactional outbox

The current reference design has a production hardening gap:

~~~text
save investigation state
        |
        v
publish adjudication
~~~

If the database commit succeeds and Kafka publication fails, the adjudication event needs another reliable publication mechanism.

The scaled design should use:

~~~text
Agent DB transaction
       |
       +--> investigation state
       +--> adjudication outbox
                |
              commit
                |
                v
          agent outbox relay
                |
                v
      transactions.adjudicated
~~~

This gives the investigation stage the same database-to-Kafka reliability boundary already used by core-service.

---

## 12. Rate limiting at scale

The reference in-memory rate limiter is per instance.

That is not sufficient for a cluster-wide quota.

### Scaled model

~~~text
Internet
   |
   v
API Gateway / WAF
   |
   +--> shared rate-limit state
   |
   v
core-service-1
core-service-2
core-service-3
...
~~~

Possible layers:

1. WAF-level protection for abusive traffic.
2. Gateway-level global quotas.
3. Service-level local protection.
4. Account/operator-specific quotas where business rules require them.

A shared Redis-backed limiter can provide distributed state when gateway-native limits are insufficient.

---

## 13. Backpressure and load shedding

Scaling is not only about adding instances.

The platform needs a controlled response when downstream capacity is exhausted.

### Backpressure chain

~~~text
Traffic spike
    |
    v
API admission control
    |
    v
PostgreSQL
    |
    v
Kafka buffering
    |
    v
bounded consumers
    |
    v
bounded gRPC concurrency
    |
    v
case-data
~~~

Each boundary should have a finite capacity.

### Load shedding

Possible load-shedding points include:

- reject excessive API traffic with 429;
- enforce per-tenant/account quotas;
- stop consuming aggressively when a downstream dependency is unhealthy;
- send unrecoverable events to DLQ;
- prioritize mandatory AML processing over non-critical analytics.

Load shedding should be explicit and observable.

---

## 14. Retry storms

At scale, retries can multiply load during an outage.

Example:

~~~text
100 consumers
x
3 retries
=
up to 300 additional attempts
~~~

If every retry immediately hits an already-failing dependency, the outage becomes worse.

Use:

- exponential backoff;
- jitter;
- selective retries;
- circuit breakers;
- bounded concurrency;
- retry budgets.

Do not retry permanent errors such as invalid schemas indefinitely.

---

## 15. Circuit breaker and bulkhead boundaries

A circuit breaker around case-data calls can prevent an unhealthy dependency from consuming all agent capacity.

~~~text
agent workers
     |
     v
circuit breaker
     |
  +--+--+
  |     |
open  closed
  |     |
fail   gRPC
fast
~~~

Bulkheads isolate resource pools.

For example:

~~~text
Agent capacity
 |
 +--> screening/investigation workers
 |
 +--> DB connections
 |
 +--> gRPC concurrency
 |
 +--> outbound network
~~~

A failure in one resource pool should not consume all resources needed by another workload.

---

## 16. Multi-AZ and availability

For production availability:

~~~text
                 Load Balancer
                  /    |    \
                 /     |     \
               AZ-1   AZ-2   AZ-3
                |      |      |
             services services services
~~~

Kafka brokers should be distributed across availability zones with replication.

PostgreSQL should use a highly available primary/replica strategy.

The exact topology depends on the cloud/provider and required recovery objectives.

---

## 17. Kafka replication and durability

For production Kafka:

- use multiple brokers;
- replicate important topics;
- configure appropriate replication factor;
- use producer acknowledgements that match durability requirements;
- monitor under-replicated partitions;
- monitor ISR health;
- define retention according to replay requirements.

The objective is not simply "Kafka is durable."

The system should explicitly define:

- acceptable data loss;
- recovery point objective;
- recovery time objective;
- replay window;
- retention period.

---

## 18. Consumer rebalancing

Consumer groups rebalance when:

- instances join;
- instances leave;
- consumers fail;
- partition assignments change.

Large or slow investigations can make rebalancing more sensitive.

At scale:

- use stable consumer lifecycles;
- tune poll intervals;
- avoid blocking beyond the configured limits;
- monitor rebalance frequency;
- use cooperative assignment strategies where appropriate;
- keep processing bounded.

Frequent rebalances reduce useful throughput.

---

## 19. Observability for scaling

Scaling decisions should be driven by metrics.

### Kafka

Monitor:

- consumer lag
- records/sec
- partition utilization
- rebalance count
- under-replicated partitions
- producer latency

### PostgreSQL

Monitor:

- CPU
- connections
- connection pool utilization
- query latency
- lock contention
- I/O
- replication lag
- table/index growth
- outbox backlog

### Services

Monitor:

- CPU
- memory
- GC
- request latency
- throughput
- error rate
- thread-pool utilization
- queue depth

### Investigation

Monitor:

- investigation latency
- gRPC latency
- gRPC timeout rate
- investigation depth
- cases/sec
- escalation rate
- worker saturation

---

## 20. Capacity planning

A useful starting model is:

~~~text
required consumer capacity
    =
incoming events/sec
    /
sustainable events/sec per consumer
~~~

Then account for:

- peak traffic;
- recovery after outages;
- retry traffic;
- headroom;
- uneven partition distribution.

For example, if a consumer sustainably handles 100 events/sec and peak traffic is 700 events/sec:

~~~text
700 / 100 = 7 consumers
~~~

Do not deploy exactly seven if the system needs fault tolerance and burst headroom.

Capacity planning should include spare capacity for at least one or more failed instances and recovery bursts according to the required availability target.

---

## 21. Autoscaling signals

CPU alone is not enough for an event-driven system.

Useful autoscaling signals include:

- Kafka consumer lag;
- records processed/sec;
- processing latency;
- queue depth;
- CPU;
- memory;
- gRPC latency;
- database connection pressure.

Example:

~~~text
consumer lag increases
        |
        v
more consumer replicas
        |
        v
partition availability check
        |
        v
throughput increases
        |
        v
lag decreases
~~~

If lag increases but all partitions already have active consumers, adding replicas will not help. More partitions or a faster consumer implementation may be required.

---

## 22. Replay and disaster recovery

Kafka retention and DLQ replay provide operational recovery mechanisms.

A controlled replay process should support:

1. identifying the failed event range;
2. validating the root cause is fixed;
3. replaying into the appropriate topic or recovery path;
4. relying on consumer idempotency;
5. monitoring side effects;
6. recording replay metadata.

Do not manually replay production events without an audit trail.

Database backups and point-in-time recovery are also required because Kafka replay cannot replace authoritative database recovery.

---

## 23. Schema evolution

At scale, Kafka event contracts become long-lived interfaces.

Use explicit schema versioning.

~~~text
transactions.ingested v1
        |
        +--> consumers

transactions.ingested v2
        |
        +--> compatible consumers
~~~

Prefer backward-compatible changes:

- add optional fields;
- avoid changing field meaning;
- avoid silently changing units;
- version breaking changes.

Schema validation should happen before events reach business consumers.

---

## 24. Security at scale

Production security should include:

- centralized identity provider;
- OIDC/OAuth2;
- short-lived credentials/tokens;
- service-to-service authentication;
- TLS for Kafka and gRPC;
- network segmentation;
- secret management;
- least-privilege database roles;
- encrypted storage;
- audit access controls.

Security should apply to internal service boundaries as well as the public API.

---

## 25. Data retention and partitioning

Large AML workloads create substantial historical data.

Candidate partitioning dimensions include:

- occurred_at/time;
- transaction date;
- account lifecycle;
- archival state.

Time-based partitioning is particularly useful for audit and historical transaction data because retention and archival can operate on complete partitions.

Do not partition every table automatically. Partition only where data volume, retention, or query patterns justify the added complexity.

---

## 26. Scaling bottleneck checklist

When throughput drops, investigate in this order:

~~~text
Kafka lag?
   |
   +-- yes --> partition/consumer capacity
   |
DB latency?
   |
   +-- yes --> queries/connections/I/O
   |
gRPC latency?
   |
   +-- yes --> case-data capacity/network
   |
Retry spike?
   |
   +-- yes --> dependency failure/retry storm
   |
Hot partition?
   |
   +-- yes --> key distribution/order trade-off
   |
CPU/memory saturation?
   |
   +-- yes --> service capacity
~~~

The important lesson is to scale the actual bottleneck rather than blindly adding replicas.

---

## 27. Scaled architecture checklist

Before calling the platform production-scale, verify:

- [ ] Kafka has multiple brokers and appropriate replication.
- [ ] Main topics have enough partitions for expected concurrency.
- [ ] Consumer groups have independent scaling policies.
- [ ] Account-key hot partitions are monitored.
- [ ] Core-service is horizontally scalable.
- [ ] Agent investigation concurrency is bounded.
- [ ] gRPC channels are reused.
- [ ] gRPC deadlines are enforced.
- [ ] Circuit breaker/bulkhead policies exist where needed.
- [ ] PostgreSQL has HA and backup/PITR.
- [ ] Connection pools are sized cluster-wide.
- [ ] Core outbox relay scales safely.
- [ ] Agent-side adjudication outbox exists.
- [ ] Global rate limiting exists.
- [ ] Kafka lag drives autoscaling decisions.
- [ ] DLQ growth generates alerts.
- [ ] Replay tooling is controlled and audited.
- [ ] Event schemas are versioned.
- [ ] Metrics cover Kafka, DB, gRPC, API, and investigation latency.
- [ ] Recovery objectives are defined and tested.

---

## 28. Related system-design topics

The TradeSentry scaling problem connects directly to these broader system-design topics:

### Messaging

- Kafka partitioning
- Consumer groups
- Ordering
- Consumer rebalancing
- Retention
- Replay
- Dead-letter queues

### Distributed systems

- At-least-once delivery
- Idempotency
- Transactional outbox
- Backpressure
- Load shedding
- Failure isolation
- Distributed coordination

### Databases

- Read replicas
- Connection pooling
- HA
- Partitioning
- Indexing
- Point-in-time recovery

### Performance

- Rate limiting
- Caching
- Batching
- Concurrency limits
- Queueing
- Capacity planning

### Reliability

- Circuit breakers
- Bulkheads
- Retry budgets
- Exponential backoff
- Jitter
- Graceful degradation

### Infrastructure

- Horizontal autoscaling
- Load balancing
- Multi-AZ deployment
- Service discovery
- Observability

---

## 29. Final mental model

The scaled TradeSentry architecture can be reduced to:

~~~text
Protect admission
      |
      v
Persist before publish
      |
      v
Buffer with Kafka
      |
      v
Scale by partitions
      |
      v
Process idempotently
      |
      v
Bound synchronous dependencies
      |
      v
Protect downstream services
      |
      v
Persist decisions and audit
      |
      v
Observe lag, latency, errors and backlog
      |
      v
Scale the actual bottleneck
~~~

The key distinction is:

**The current architecture defines the business workflow. The scaled architecture changes the capacity and reliability characteristics around that same workflow.**
