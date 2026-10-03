# Configuration Reference

Every service is configured through `application.yml` with environment-variable
overrides. Most infrastructure variables have localhost-friendly defaults. API credentials are intentionally required and have no defaults.
Docker Compose sets the overrides needed to run inside the compose network.

## Contents

- [core-service](#core-service)
- [agent-service](#agent-service)
- [case-data-service](#case-data-service)
- [Infrastructure](#infrastructure)

## core-service

HTTP port: `8080`

| Variable         | Default       | Description                         |
|------------------|---------------|-------------------------------------|
| `KAFKA_BOOTSTRAP`| `localhost:9092` | Kafka bootstrap servers          |
| `DB_HOST`        | `localhost`   | PostgreSQL host                     |
| `DB_PORT`        | `5432`        | PostgreSQL port                     |
| `DB_NAME`        | `tradesentry` | Database name                       |
| `DB_USER`        | `tradesentry` | Database user                       |
| `DB_PASSWORD`    | `tradesentry` | Database password                   |
| `TRADESENTRY_API_USER` | required | HTTP Basic username; no default |
| `TRADESENTRY_API_PASSWORD` | required | HTTP Basic password; no default |
| `TRADESENTRY_TRACE_SAMPLE` | `0.1` | OpenTelemetry trace sampling probability |
| `OTEL_EXPORTER_OTLP_ENDPOINT` | `http://localhost:4318/v1/traces` | OTLP trace endpoint |
| `CORE_KAFKA_MAX_POLL_RECORDS` | `50` | Maximum records returned per core consumer poll |

Notes:

- JPA runs with `ddl-auto: update`, so the `transactions` table is created and
  evolved automatically. This is convenient for development; a managed migration
  tool would be preferable for production.
- Kafka consumers set `spring.json.use.type.headers: false` and bind to
  `com.tradesentry.core.events.model.TransactionEvent`.

## agent-service

HTTP port: `8081`

| Variable          | Default                    | Description                                  |
|-------------------|----------------------------|----------------------------------------------|
| `KAFKA_BOOTSTRAP` | `localhost:9092`           | Kafka bootstrap servers                      |
| `CASE_DATA_GRPC`  | `static://localhost:9090`  | gRPC address of case-data-service            |
| `AGENT_KAFKA_MAX_POLL_RECORDS` | `10` | Maximum records returned per agent poll |
| `AGENT_KAFKA_MAX_POLL_INTERVAL_MS` | `300000` | Kafka max poll interval; must exceed worst-case processing time |
| `AGENT_GRPC_DEADLINE_MS` | `2000` | Case-data gRPC deadline in milliseconds |

Notes:

- The Kafka consumer group is `investigation-agent`.
- The gRPC channel `case-data` uses plaintext negotiation.
- Kafka consumers set `spring.json.use.type.headers: false` and bind to
  `com.tradesentry.agent.events.TransactionEvent`.

## case-data-service

gRPC port: `9090`

The service has no application-specific environment configuration beyond its
gRPC server port. Its responses are synthetic and deterministic.

## Infrastructure

Provisioned by `docker-compose.yml`.

### PostgreSQL

| Setting  | Value         |
|----------|---------------|
| Image    | `postgres:16` |
| Database | `tradesentry` |
| User     | `tradesentry` |
| Password | `tradesentry` |
| Port     | `5432`        |

### Security and observability

The REST security boundary uses environment-supplied API credentials. Both
`TRADESENTRY_API_USER` and `TRADESENTRY_API_PASSWORD` are required and have no
default values. Do not commit production credentials.

The application supports `TRADESENTRY_TRACE_SAMPLE` (default `0.1`) and
`OTEL_EXPORTER_OTLP_ENDPOINT` for tracing.

For production, replace reference Basic authentication with an enterprise
OIDC/OAuth2 identity provider.

### Kafka

| Setting | Value               |
|---------|---------------------|
| Image   | `apache/kafka:3.9.0`|
| Mode    | KRaft (broker + controller, no ZooKeeper) |
| Port    | `9092` (PLAINTEXT)  |

Kafka runs single-node with replication factor 1 for all internal topics. The
`CONTROLLER` listener binds `kafka:9093` (a routable host) rather than
`0.0.0.0`, which the `apache/kafka:3.9.0` storage-format step requires.
The main application topics are created by `core-service` at startup via
`TopicBuilder` beans — see
[ARCHITECTURE.md](ARCHITECTURE.md#kafka-topics-and-consumer-groups).
