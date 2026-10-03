# API Reference

TradeSentry exposes a REST API on `core-service` for transaction intake and a
gRPC service on `case-data-service` for investigation-time lookups. This
document describes both contracts.

## Contents

- [REST API (core-service)](#rest-api-core-service)
  - [Submit a transaction](#submit-a-transaction)
  - [Get a transaction](#get-a-transaction)
- [gRPC API (case-data-service)](#grpc-api-case-data-service)
  - [GetAccountHistory](#getaccounthistory)
  - [RetrieveSimilarCases](#retrievesimilarcases)
- [Event contract](#event-contract)

## REST API (core-service)

Base URL: `http://localhost:8080`

### Submit a transaction

Accepts a transaction for monitoring. The call returns after the transaction
and its corresponding outbox event are durably committed to PostgreSQL.
Screening, Kafka publication, investigation, and case management happen
asynchronously. Kafka publication is performed by the outbox relay after the
request transaction commits.

```
POST /api/transactions
Content-Type: application/json
```

Request body:

| Field                 | Type    | Required | Constraints                     |
|-----------------------|---------|----------|---------------------------------|
| `accountId`           | string  | yes      | Non-blank                       |
| `amount`              | number  | yes      | Greater than 0                  |
| `currency`            | string  | yes      | Non-blank                       |
| `counterpartyCountry` | string  | no       | ISO country code (e.g. `KP`)    |

Example:

```bash
curl -X POST http://localhost:8080/api/transactions \
  -H "Content-Type: application/json" \
  -d '{
        "accountId": "acc-1",
        "amount": 75000,
        "currency": "USD",
        "counterpartyCountry": "KP"
      }'
```

Response — `202 Accepted`:

```json
{
  "transactionId": "5b9c2e1a-1f4b-4a6d-8f2e-9a1c3d5e7f01",
  "status": "SUBMITTED"
}
```

Validation failures return `400 Bad Request`.

### Get a transaction

Returns the current persisted state of a transaction, including its lifecycle
status.

```
GET /api/transactions/{id}
```

Example:

```bash
curl http://localhost:8080/api/transactions/5b9c2e1a-1f4b-4a6d-8f2e-9a1c3d5e7f01
```

Response — `200 OK`:

```json
{
  "id": "5b9c2e1a-1f4b-4a6d-8f2e-9a1c3d5e7f01",
  "accountId": "acc-1",
  "amount": 75000,
  "currency": "USD",
  "counterpartyCountry": "KP",
  "status": "FLAGGED",
  "createdAt": "2026-07-20T10:15:30Z",
  "updatedAt": "2026-07-20T10:15:31Z"
}
```

If no transaction exists for the id, the call returns `404 Not Found`. See
[ARCHITECTURE.md](ARCHITECTURE.md#transaction-lifecycle) for the meaning of each
`status` value.

## gRPC API (case-data-service)

The service listens on port `9090` (plaintext). The contract is defined in
[`proto/src/main/proto/casedata.proto`](../proto/src/main/proto/casedata.proto).

```protobuf
service CaseDataService {
  rpc GetAccountHistory (AccountHistoryRequest) returns (AccountHistoryResponse);
  rpc RetrieveSimilarCases (SimilarCasesRequest) returns (stream SimilarCase);
}
```

### GetAccountHistory

Unary call. Returns aggregate history and a risk band for an account.

Request — `AccountHistoryRequest`:

| Field           | Type    | Description                        |
|-----------------|---------|------------------------------------|
| `account_id`    | string  | Account to look up                 |
| `lookback_days` | int32   | History window in days             |

Response — `AccountHistoryResponse`:

| Field                    | Type    | Description                                   |
|--------------------------|---------|-----------------------------------------------|
| `account_id`             | string  | Echoed account id                             |
| `total_transactions`     | int32   | Transactions in the lookback window           |
| `avg_transaction_amount` | double  | Average transaction amount                    |
| `max_transaction_amount` | double  | Largest transaction amount                    |
| `prior_flags`            | int32   | Number of prior screening flags               |
| `has_prior_sar`          | bool    | Whether a SAR has been filed before           |
| `risk_band`              | string  | `LOW`, `MEDIUM`, or `HIGH`                     |

### RetrieveSimilarCases

Server-streaming call. Streams prior cases that resemble the transaction under
investigation, most similar first.

Request — `SimilarCasesRequest`:

| Field                  | Type    | Description                              |
|------------------------|---------|------------------------------------------|
| `transaction_summary`  | string  | Free-text summary of the transaction     |
| `amount`               | double  | Transaction amount                       |
| `counterparty_country` | string  | Counterparty country code                |
| `max_results`          | int32   | Maximum cases to stream                  |

Response — a stream of `SimilarCase`:

| Field              | Type    | Description                                        |
|--------------------|---------|----------------------------------------------------|
| `case_id`          | string  | Identifier of the prior case                       |
| `similarity_score` | double  | Similarity to the query, in `[0, 1]`               |
| `outcome`          | string  | `SAR_FILED`, `ESCALATED`, or `CLEARED`             |
| `summary`          | string  | Short description of the prior case                |

> The case-data responses are currently synthetic and deterministic (derived
> from the `accountId` hash and request parameters), so investigations are
> reproducible during development.

## Event contract

Services also communicate over Kafka using a shared JSON event shape,
`TransactionEvent`. Each service defines its own copy of the record; they agree
on field names, order, and types.

| Field                 | Type            | Populated at stage             |
|-----------------------|-----------------|--------------------------------|
| `transactionId`       | UUID            | all                            |
| `accountId`           | string          | all (also the Kafka key)       |
| `amount`              | number          | all                            |
| `currency`            | string          | all                            |
| `counterpartyCountry` | string          | all                            |
| `reason`              | string          | flagged / adjudicated          |
| `decision`            | string          | adjudicated                    |
| `occurredAt`          | timestamp (ISO) | all                            |
| `ruleVersion`         | string          | screening / adjudicated       |
| `scoringVersion`      | string          | adjudicated                    |
| `decisionPolicyVersion` | string        | adjudicated                    |
| `correlationId`       | string          | pipeline lineage               |

Event IDs and correlation IDs are distinct: `eventId` identifies one event instance, `transactionId` identifies the business transaction, and `correlationId` links the asynchronous request lineage.

The event contract is designed for at-least-once delivery; consumers must be idempotent.

See [ARCHITECTURE.md](ARCHITECTURE.md#kafka-topics-and-consumer-groups) for the
topics these events flow through.
