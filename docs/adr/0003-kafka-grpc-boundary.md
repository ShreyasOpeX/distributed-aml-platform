# ADR 0003: Kafka and gRPC Boundary

## Decision
Kafka connects independently progressing pipeline stages. gRPC supplies synchronous case-data reads required during an investigation.

## Consequence
gRPC calls need explicit deadlines and failure containment.

The reference agent uses blocking gRPC in the Kafka listener, so `max.poll.interval.ms` must exceed worst-case investigation processing time. At production scale, bounded worker pools or asynchronous stubs can isolate downstream latency from Kafka listener capacity.
