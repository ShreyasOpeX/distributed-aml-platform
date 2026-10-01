# ADR 0003: Kafka and gRPC Boundary

## Decision
Kafka connects independently progressing pipeline stages. gRPC supplies synchronous case-data reads required during an investigation.

## Consequence
gRPC calls need explicit deadlines and failure containment.
