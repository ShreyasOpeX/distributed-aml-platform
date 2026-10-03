# ADR 0006: Scaling and Backpressure

## Decision
Scale Kafka consumers by partition ownership, keep investigation concurrency
bounded, and control API admission with rate limiting.

The reference deployment uses three partitions for the main pipeline topics.
Production capacity should be determined from throughput, processing latency,
recovery requirements, and hot-key distribution rather than a fixed partition
count.

The scaled design should also use bounded investigation workers, explicit gRPC
deadlines, circuit breakers/bulkheads where appropriate, and shared/global rate
limits at the gateway or distributed layer.

## Why
Threads cannot exceed partition parallelism, and unlimited admission can
exhaust memory before downstream stages catch up. Increasing application
replicas without enough Kafka partitions does not create additional Kafka
parallelism.

## Consequences
accountId remains the Kafka key because per-account ordering is valuable, but a
high-volume account can create a hot partition. Changing the key therefore
requires an explicit ordering trade-off.

See [../SCALING.md](../SCALING.md) for the complete production-scale design.
