# Development Guide

How to build, run, and test TradeSentry locally.

## Contents

- [Prerequisites](#prerequisites)
- [Repository layout](#repository-layout)
- [Building](#building)
- [Running with Docker Compose](#running-with-docker-compose)
- [Running services locally](#running-services-locally)
- [Testing](#testing)
- [Exercising the pipeline](#exercising-the-pipeline)
- [Troubleshooting](#troubleshooting)

## Prerequisites

| Tool           | Version | Notes                                        |
|----------------|---------|----------------------------------------------|
| JDK            | 21      | Required to build and run the services       |
| Maven          | 3.9+    | A wrapper is not committed; use a local Maven |
| Docker         | recent  | For the full stack and infrastructure        |
| Docker Compose | v2      | `docker compose` (plugin form)               |

## Repository layout

```
distributed-aml-platform/
├── pom.xml                  # Reactor (parent) POM
├── docker-compose.yml       # Full stack: infra + services
├── proto/                   # Shared protobuf contract + generated stubs
├── core-service/            # Intake, screening, case management, audit (HTTP 8080)
├── agent-service/           # Investigation state-graph agent (HTTP 8081)
├── case-data-service/       # gRPC case-data provider (gRPC 9090)
└── docs/                    # Project documentation
```

The build is a Maven multi-module reactor. `proto` is built first and the other
modules depend on its generated stubs.

## Building

Build every module and run the tests:

```bash
mvn clean install
```

Build a single service and everything it depends on (`-am` = also make):

```bash
mvn -pl agent-service -am clean package
```

Skip tests for a faster packaging build:

```bash
mvn clean package -DskipTests
```

## Running with Docker Compose

The compose file provisions PostgreSQL and Kafka (KRaft mode, no ZooKeeper) and
builds and runs all three services.

```bash
# Build images and start everything
docker compose up --build

# Start in the background
docker compose up --build -d

# Tail service logs
docker compose logs -f core-service agent-service case-data-service

# Stop and remove containers
docker compose down
```

Exposed ports:

| Service            | Port | Protocol |
|--------------------|------|----------|
| core-service       | 8080 | HTTP     |
| agent-service      | 8081 | HTTP     |
| case-data-service  | 9090 | gRPC     |
| PostgreSQL         | 5432 | TCP      |
| Kafka              | 9092 | TCP      |

Services wait for Kafka (and PostgreSQL, for core-service) to pass their health
checks before starting.

## Running services locally

You can run the services from your IDE or the command line while keeping the
infrastructure in Docker. Start only the infrastructure first:

```bash
docker compose up postgres kafka
```

Then run each service. Every service reads its connection settings from
environment variables with localhost defaults, so no configuration is needed
when Kafka and PostgreSQL are published on localhost:

```bash
mvn -pl case-data-service spring-boot:run
mvn -pl core-service spring-boot:run
mvn -pl agent-service spring-boot:run
```

See [CONFIGURATION.md](CONFIGURATION.md) for the full list of environment
variables and their defaults.

## Testing

Run the whole suite:

```bash
mvn test
```

Run tests for a single module:

```bash
mvn -pl agent-service test
```

The most substantive tests live in `agent-service`:

- `StateGraphTest` — verifies the graph engine: linear execution, conditional
  routing, bounded cycles, and the step-cap safeguard against runaway loops.
- `InvestigationGraphTest` — drives the full investigation graph with an
  in-memory `CaseDataClient` fake, asserting that strong signals escalate, weak
  signals clear, and borderline cases loop before deciding.

## Exercising the pipeline

With the stack running, submit transactions that trigger different paths.

Escalation (large amount + high-risk country):

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"acc-1","amount":75000,"currency":"USD","counterpartyCountry":"KP"}'
```

Early clear (small, low-risk):

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"acc-2","amount":250,"currency":"USD","counterpartyCountry":"US"}'
```

Possible structuring (just under the reporting threshold):

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{"accountId":"acc-3","amount":9500,"currency":"USD","counterpartyCountry":"US"}'
```

Watch the logs to follow screening, investigation, and case-management
decisions. Poll `GET /api/transactions/{id}` to observe the status transition
from `SUBMITTED` to its final state.

## Documentation map

- [ARCHITECTURE.md](ARCHITECTURE.md) — current reference workflow and boundaries.
- [SCALING.md](SCALING.md) — production-scale evolution and capacity planning.
- [FAILURE-MODES.md](FAILURE-MODES.md) — failure behavior and recovery.
- [PRODUCTION-HARDENING.md](PRODUCTION-HARDENING.md) — hardening decisions.
- [adr/](adr/) — architectural decisions.

## Troubleshooting

- **Kafka fails to start / controller listener errors.** The compose Kafka
  config binds the `CONTROLLER` listener to a routable host (`kafka:9093`)
  because `apache/kafka:3.9.0` rejects an advertised `0.0.0.0` controller. Do
  not change these listener addresses without understanding that constraint.
- **Deserialization type errors on Kafka consumers.** Each service ignores the
  producer's `__TypeId__` header (`spring.json.use.type.headers: false`) and
  binds to its own default event type. If you rename or move the event record,
  update `spring.json.value.default.type` accordingly.
- **PostgreSQL timezone error on core-service.** Windows JVMs report the legacy
  `Asia/Calcutta` alias, which the `postgres:16` image's tzdata no longer
  contains. `core-service` pins `-Duser.timezone=Asia/Kolkata` via the
  spring-boot-maven-plugin configuration; keep that when running locally on
  Windows.
- **Agent cannot reach case-data-service.** Confirm the gRPC channel address.
  It defaults to `static://localhost:9090` locally and is overridden to
  `static://case-data-service:9090` inside Docker via `CASE_DATA_GRPC`.
