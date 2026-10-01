# ADR 0002: At-Least-Once Plus Idempotency

## Decision
Use Kafka at-least-once delivery and an inbox key of eventId plus consumerName for side-effecting consumers.

## Why
It makes crashes and replay recoverable without claiming global exactly-once semantics.
