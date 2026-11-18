# 00 Stack check

A Quarkus and Camel service that proves the whole local stack works. `POST /orders` takes an `Order` as JSON, rejects an invalid one with 400, records it in the `orders` PostgreSQL schema (Flyway-migrated), and publishes a valid one to the Kafka topic `apa.orders.placed`, keyed by order id. A second route consumes the topic and logs each record. `POST /assistant` takes a plain-text question and answers it through a local langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama` (not the Camel langchain4j-agent component), which can call an `orderStatus` tool for a seeded status. Traces, metrics and logs leave the service over OTLP (http/protobuf) to the Grafana LGTM container with `service.name` `stack-check`. SmallRye Health serves `/q/health`.

## Run

`./verify.sh` needs Docker Engine with the compose plugin and the pulled Ollama model (`../../scripts/ollama-model.sh pull`, once); it builds the service and the shared domain model inside the image. `mvn -B verify` needs JDK 25 and Maven.

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` starts Kafka, PostgreSQL, Apicurio Registry, Keycloak, Ollama and LGTM plus the service, runs the checks below and removes everything with `down -v`. Set `KEEP=1` to leave the stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose stack needed, and the assistant's Ollama dev service stays off since the tests never call it). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Expected output

```
PASS 1: apa-kafka is healthy
...
PASS 7: apa-stack-check is healthy
PASS 8: valid Order accepted with 2xx
PASS 9: the record keyed O-1794344257 arrives on the apa.orders.placed topic
PASS 10: the orders schema round trip recorded O-1794344257 in PostgreSQL
PASS 11: Apicurio Registry reports ready
PASS 12: Keycloak realm apa OIDC discovery is reachable
PASS 13: Tempo has a trace for service stack-check
PASS 14: Prometheus has a metric from stack-check
PASS 15: Loki has a log line from stack-check
PASS 16: the assistant's tool-call round trip invokes orderStatus (observed via log, not model prose)
PASS 17: negative witness: invalid Order (quantity 0) gets 400
PASS 18: negative witness: record count unchanged (1)
18/18 checks passed
```

The checks cover: seven healthy containers; a valid order accepted, its record on the topic and its row in the `orders.stack_check` table; Apicurio ready; the Keycloak `apa` realm's OIDC discovery document; a trace in Tempo, a metric in Prometheus and a log line in Loki for `stack-check`; the assistant's tool-call round trip (asserted by the `orderStatus` tool's own log line, since qwen3's tool calling is nondeterministic and a 200 response alone does not prove the tool ran); and the negative witness: an invalid order (quantity 0) gets 400 and the topic record count is unchanged.

## Tests and checks in detail

The tests pass on a Dev Services Kafka and PostgreSQL running the pinned broker and the same PostgreSQL 18.6 minor as the compose stack's CloudNativePG operand image (Dev Services itself runs the plain `postgres` image, since the CNPG operand image needs its own non-standard entrypoint).

## Experiments

Each row changes a single thing and names the checks that fail.

| Switch | Effect | Failing checks |
|---|---|---|
| Remove the `onException` clause | An invalid Order gets 500 instead of 400; the record count still passes because nothing reached the topic | 17 (18 still passes) |
| Change `Topics.ORDERS_PLACED` in the intake route to `"apa.orders.placed-v2"` | No record arrives on `apa.orders.placed`, and the consumer route has nothing to log | 9 and 15 |

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../OrderIntakeRoute.java` | HTTP to Kafka route, with validation and the PostgreSQL touch |
| `src/main/java/.../OrderLogRoute.java` | Kafka consumer route |
| `src/main/java/.../OrderRecordStore.java` | `@Transactional` insert and select against the `orders` schema |
| `src/main/java/.../OrderAssistant.java` | langchain4j `AiService` interface (quarkus-langchain4j-ollama) |
| `src/main/java/.../OrderStatusTool.java` | the `@Tool` the assistant can call, with a Micrometer counter and a log line |
| `src/main/java/.../AssistantRoute.java`, `AssistantBean.java` | HTTP entry point into the assistant |
| `src/main/resources/db/migration/V1__orders.sql` | Flyway migration for the `orders` schema |
| `src/main/resources/application.properties` | OTLP export, datasource, Ollama, Dev Services images |
| `compose.yaml` | includes the shared infrastructure and adds the service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above |
