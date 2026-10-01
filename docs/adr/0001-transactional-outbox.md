# ADR 0001: Transactional Outbox

## Decision
Persist the business transaction and its Kafka intent in PostgreSQL in one transaction, then relay outbox rows asynchronously.

## Alternatives
Direct publish is simpler but can lose events. Two-phase commit is heavier. CDC/Debezium is viable later but adds infrastructure.

## Consequence
The relay is at-least-once, so consumers must be idempotent.
