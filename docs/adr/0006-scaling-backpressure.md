# ADR 0006: Scaling and Backpressure

## Decision
Scale Kafka consumers by partition ownership and bound API admission with rate limiting.

## Why
Threads cannot exceed partition parallelism, and unlimited admission can exhaust memory before downstream stages catch up.
