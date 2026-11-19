---
title: "Prerequisites and the two stacks"
order: 0
part: "Foundations"
description: "The tools, exact pins and two local stacks every example in this catalog runs on: the application stack a request and a tool call travel through, and the observability stack that watches them."
duration: "30 minutes"
---

## Learning objectives

- Install the toolchain: Docker Engine and its compose plugin, plus SDKMAN's JDK, Maven, JBang and Quarkus CLI.
- Bring up and tear down the two independent compose stacks without one depending on the other.
- Lay out and run an example against its exact pins, with a fresh-state `verify.sh` and a negative witness.
- Read the OTLP traces, metrics and logs an example produces in Grafana.

## Why this matters

Every example in this catalog runs on two local Docker Compose stacks, started
and stopped independently. The application stack carries the actual request:
an HTTP call, an event on Kafka, or a tool call from an agent. The
observability stack receives the OTLP signals that request produces. Neither
needs the other running to work; the application stack passes its checks on
its own, and the observability stack only adds a place to look.

{% include excalidraw.html file="00-the-two-stacks" alt="The application compose stack (Kafka, PostgreSQL, Apicurio, Keycloak, Ollama) and the separate observability compose stack (Grafana LGTM), with the request path and the OTLP path between them" caption="Figure 0.1: Two stacks, started and stopped independently" %}

## What to install

A Fedora or RHEL host with a container engine and its Compose plugin. Install
Docker Engine from [Docker's repository for your distribution](https://docs.docker.com/engine/install/)
and add the local user to the `docker` group so no command needs `sudo`.
Nothing else runs as root, and nothing here changes host firewall, kernel or
network settings.

```bash
for t in git curl docker; do command -v "$t" >/dev/null || echo "missing: $t"; done
docker compose version
```

[SDKMAN](https://sdkman.io/) installs the JDK, Maven, JBang and the Quarkus
CLI, all under `$HOME/.sdkman`:

```bash
curl -s "https://get.sdkman.io" | bash
source "$HOME/.sdkman/bin/sdkman-init.sh"
sdk install java 25.0.5-tem
sdk install maven 3.9.16
sdk install jbang
sdk install quarkus
java -version && mvn -version && jbang version && quarkus version
```

Every service this book ships builds inside its own UBI 10 image, so the host
never needs a JDK to run an example: `./verify.sh` builds the service with
`docker compose build`, which runs Maven inside the image, not on the host.
The host JDK, Maven, JBang and the Quarkus CLI exist for a different job:
stepping through a service in an IDE, running `mvn -B verify` for the fast
Dev Services test loop, and driving a single file with `jbang` when a service
is small enough to prototype as one. JBang and the Quarkus CLI carry no
version pin of their own: neither artifact ships inside any image, and both
resolve the Quarkus platform pin below whenever they touch a project.

## Pins

Every pin is an exact release, chosen once and moved only for a security fix,
a real incompatibility or a feature an example needs. Nothing in this catalog
needs a license or a subscription.

| Component | Pin (exact) | Why this one |
|---|---|---|
| JDK | Temurin 25.0.5 | Long-term-support line; the minimum for every example |
| Maven | 3.9.16 | 3.10.1 is the current overall release, held on a Quarkus-reported incompatibility |
| Quarkus platform | 3.40.1.2 | Long-term-support stream |
| Apache Camel | 4.22.2 | Long-term-support patch, imported ahead of the platform's own Camel pin |
| Apache Kafka | 4.3.2, KRaft, one node | The version a later Kubernetes run target also supports |
| PostgreSQL | 18.6, CloudNativePG operand image | Same minor on compose and on Quarkus Dev Services |
| Apicurio Registry | 3.3.4 | Schema registry for the catalog's async and schema-evolution patterns |
| Keycloak | 26.8.2 | The book-wide identity provider, realm `apa` |
| Ollama | 0.40.16, pinned by manifest digest | The local model runtime behind every langchain4j consumer |
| Model | `qwen3:4b-instruct-2507-q4_K_M` | Apache-2.0, tool-calling capable, small enough for a laptop CPU |
| UBI 10 OpenJDK, build and runtime | 1.24-20 | Every image this catalog builds starts here |

`_plans/versions.md` carries the full table, the survey date and the source
checked for each row.

## The application stack

`examples/_infra/compose.yaml` declares five services on one Docker network.
An example includes the files it needs and adds its own service alongside
them.

| Service | Host port | Role |
|---|---|---|
| Kafka (KRaft, one node) | 9092 | The log an agent-triggered event lands on; containers reach it at `kafka:9094` |
| PostgreSQL, CloudNativePG operand image | 5432 | State for every service, one schema each |
| Apicurio Registry | 8081, health 19000 | Schemas and compatibility rules for the async patterns |
| Keycloak | 8180 | The identity provider for the whole catalog, realm `apa` |
| Ollama | 11434 | The local model runtime; a helper script pulls the pinned model once into a named volume that survives a fresh restart |

Pull the model once, then start the stack:

```bash
scripts/ollama-model.sh pull    # once; the volume survives `docker compose down -v`
scripts/ollama-model.sh check   # confirms the pinned model digest is present
```

Every service carries a health check, so `docker compose up --wait` returns
only once all five answer ready. Cockpit, often enabled on Fedora and RHEL,
holds host port 9090, which is why Prometheus below publishes on 19090
instead.

## The observability stack

`examples/_infra/compose.lgtm.yaml` runs one `grafana/otel-lgtm` container:
Loki, Grafana, Tempo and Prometheus behind a single OTLP endpoint. It is a
separate compose file, started and stopped on its own, so observability can
come up or go down without touching the application stack the checks
depend on. The stack-check example brings it up to confirm that traces,
metrics and logs arrive; past that it stays optional, there so a signal can
be inspected by hand after a run.

| Signal | Host port | Data source in Grafana |
|---|---|---|
| OTLP (gRPC and HTTP) | 4317, 4318 | Every service's export target |
| Grafana | 3000 | No login required |
| Tempo | 3200 | Traces |
| Loki | 3100 | Logs |
| Prometheus | 19090 | Metrics |

Each service exports traces, metrics and logs over OTLP with `service.name`
set to its own name, so a Grafana Explore query by that name finds everything
one service produced in one place.

## The two consuming harnesses

Two different programs call the tools this catalog's services expose, and
both matter for different reasons.

Claude Code is the reference harness: an interactive agent that registers an
MCP server, discovers its tools, resources and prompts, and decides for
itself which to call. It is how a reader actually drives an example by hand,
and it is the harness every cross-stack callout in a later pattern page
targets first.

A direct `quarkus-langchain4j` consumer, backed by `quarkus-langchain4j-ollama`
against the pinned local model, is the programmatic alternative: a Java
`AiService` interface that calls the same tools without a human watching.
What it proves here is a smoke test only, one tool call round-tripped and
observed in a log line, not a model's prose. Driving a full programmatic
agent against a real tool surface, with retries, memory and multi-step
plans, is covered in its own right later in the catalog.

Both harnesses call the same backend the same way: a registered tool with a
name, a description and a typed schema. Neither cares whether the service
behind that tool is REST, GraphQL or a Kafka consumer; the tool contract is
the only thing either of them sees.

## The shipping domain

Every service in this catalog belongs to one order-to-delivery shipping
domain: a customer orders an item, the order is recorded, stock is reserved,
payment is taken, a carrier is booked and the parcel is delivered. Five
services each own one step and talk to each other only through events on
Kafka.

| Service | Owns | Reacts to | Emits |
|---|---|---|---|
| order-service | `Order`, its lifecycle | InventoryInsufficient, PaymentProcessed, PaymentFailed, ShipmentScheduled, ShipmentDelivered | OrderPlaced, OrderCancelled |
| inventory-service | `InventoryItem` stock | OrderPlaced, OrderCancelled, PaymentFailed | InventoryReserved, InventoryInsufficient |
| payment-service | `Payment` | InventoryReserved, OrderCancelled | PaymentProcessed, PaymentFailed |
| shipping-service | `Shipment`, carrier choice | PaymentProcessed | ShipmentScheduled, ShipmentInTransit, ShipmentDelivered |
| notification-service | `Notification` | every event | none |

What this catalog adds on top of that domain is a second front door. Each of
those five services gets an agent-facing surface alongside its existing one:
intent-named operations instead of raw CRUD, a tool contract instead of a
browser-shaped page, instructive errors instead of a status code alone. The
shared records (`Order`, `InventoryItem`, `Payment`, `Shipment`,
`Notification`, their status enums and the `Topics` constants) live once in
`examples/domain-model`, package `com.patterncatalyst.agentapi`, so no
example redefines them.

## How every example is laid out

```
examples/NN-slug/
  pom.xml  src/         Quarkus and Camel project
  compose.yaml          includes ../_infra files and adds the service
  Containerfile         UBI 10 multi-stage build, context is examples/
  verify.sh             starts the stack, checks the observable effect, tears down
  README.md
```

`examples/domain-model` holds the shared records and is installed into the
local Maven repository, or built inside the Containerfile, before any example
compiles against it. Every example runs two ways. `mvn -B verify` runs the
tests; Quarkus Dev Services starts whatever container a test needs, Kafka or
PostgreSQL, so no compose stack has to be running first. `./verify.sh` builds
the image, starts the compose stack fresh with `down -v --remove-orphans`
then `up -d --build --force-recreate --renew-anon-volumes --wait`, checks the
observable effect, and tears down again with `down -v` unless `KEEP=1` is
set. Containers and volumes are never reused between runs.

Every `verify.sh` includes a negative witness: a check that fails once the
pattern's own guard is removed. A script that always passes, even after the
behavior it is supposed to prove has been deleted, has not actually proven
anything.

## Build, run, observe

```bash
scripts/ollama-model.sh check
./verify.sh
```

Run from the stack-check example, this builds the service and the shared
domain model inside its own image, brings up all five application-stack
services plus the observability stack, and exercises a full round trip: a
valid order posted over HTTP, published to Kafka, recorded in PostgreSQL,
and a local model calling a registered tool to answer a status question,
alongside an invalid order rejected outright. It ends at 18/18.

Try:

- Run `KEEP=1 ./verify.sh`, then open Grafana and search Tempo for a trace
  from the stack-check service.
- Remove the route's validation guard and rerun: the invalid order now
  succeeds instead of getting rejected.

## Anti-patterns

- **Docker Desktop or podman, in place of Docker Engine and its compose plugin.** Both diverge from the engine every example here is written and verified against.
- **Running as root, or needing sudo, for any container or host operation.** Adding the local user to the `docker` group removes the need; nothing here touches host firewall, kernel or network settings.
- **A floating or `latest` tag in place of an exact pin, or reusing containers and volumes between runs.** Every live run starts from `down -v --remove-orphans`, so a pin drifts only when the table above records a reason to move it.

## References

- [Docker Engine installation](https://docs.docker.com/engine/install/)
- [SDKMAN](https://sdkman.io/)
- [Quarkus guides](https://quarkus.io/guides/)
- [Quarkus LangChain4j](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- [Ollama documentation](https://docs.ollama.com/)
- [Apache Kafka documentation](https://kafka.apache.org/documentation/)
- [Apicurio Registry documentation](https://www.apicur.io/registry/docs/)
- [Keycloak documentation](https://www.keycloak.org/documentation)
- [Grafana LGTM (Loki, Grafana, Tempo, Mimir)](https://grafana.com/oss/)
- [Claude Code documentation](https://docs.claude.com/en/docs/claude-code/overview)
