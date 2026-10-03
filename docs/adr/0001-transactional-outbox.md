# ADR 0001: Transactional Outbox

## Decision
Persist the business transaction and its Kafka intent in PostgreSQL in one transaction, then relay outbox rows asynchronously.

## Alternatives
Direct publish is simpler but can lose events. Two-phase commit is heavier. CDC/Debezium is viable later but adds infrastructure.

## Consequence
The relay is at-least-once, so consumers must be idempotent.

The outbox currently exists at the core intake boundary. The investigation agent should gain an analogous outbox before production scale so investigation-state persistence and adjudication publication can commit atomically.

The scaled design also allows multiple relay workers using `FOR UPDATE SKIP LOCKED` to divide pending rows safely.
