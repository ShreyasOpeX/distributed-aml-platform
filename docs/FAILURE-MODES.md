# Failure Modes

## Delivery semantics

TradeSentry targets at-least-once delivery with idempotent side effects.

The outbox can duplicate a record after Kafka accepts it. Kafka can redeliver records after consumer failure. The inbox makes those cases safe.

## Investigation dependency

The agent has a synchronous gRPC dependency on case-data-service. A deadline prevents an unavailable dependency from holding a consumer indefinitely.

The failure boundary is:

Kafka -> agent -> gRPC -> bounded retry -> DLQ

## Database consistency

Case management writes the transaction decision and case/SAR records in one database transaction. If that transaction rolls back, Kafka redelivers the adjudication.

## Hot keys

accountId is deliberately used as the Kafka key because per-account ordering is a business requirement. The trade-off is that a single extremely active account can create a hot partition.

If that becomes a capacity problem, changing the key requires revisiting the ordering guarantee rather than simply adding threads.

## Operational response

DLQ growth, outbox backlog, consumer lag and gRPC error rate are operational signals. They should be alerted on and investigated before increasing retry counts blindly.
