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

The platform is built as a set of independently deployable Spring Boot services
that coordinate through events rather than direct calls. A transaction flows
through four stages:

1. **Intake** — the transaction is accepted over REST and persisted.
2. **Screening** — deterministic rules decide whether it clears early or is
   flagged for investigation.
3. **Investigation** — a state-graph agent enriches the case, retrieves similar
   historical cases, scores the risk, and produces a decision.
4. **Case management** — the decision is applied; escalations open a Suspicious
   Activity Report (SAR) case.

Two transport styles are used deliberately: **Kafka** for asynchronous
choreography *between* stages, and **gRPC** for synchronous request/response
*within* the investigation stage.

## Architecture

```mermaid
flowchart LR
    client([Client]) -->|POST /api/transactions| core

    subgraph core[core-service]
        intake[Transaction intake]
        screen[Rule screening]
        casemgmt[Case management]
        audit[Audit log]
    end

    subgraph agent[agent-service]
        consumer[Flagged consumer]
        graph[Investigation graph]
    end

    subgraph casedata[case-data-service]
        grpc[gRPC case data]
    end

    intake -->|transactions.ingested| screen
    intake -->|transactions.ingested| audit
    screen -->|transactions.flagged| consumer
    consumer --> graph
    graph -->|gRPC| grpc
    graph -->|transactions.adjudicated| casemgmt
    graph -->|transactions.adjudicated| audit

    core <-->|Kafka| broker[(Kafka)]
    agent <-->|Kafka| broker
    core --- db[(PostgreSQL)]
```

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the full event flow, topic
design, and the investigation graph.

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
