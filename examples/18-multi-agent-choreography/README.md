# 18 Multi-agent choreography

Three agent-backed services that coordinate by each reacting to events on shared Kafka topics,
with no central orchestrator: an order agent, a shipping agent and a notification agent. The
"order" side emits the initial event; everything after that is a chain of reactions.

```
POST /orders -> order agent -> apa.orders.placed
                                     |
                                     v
                           shipping agent reacts
                                     |
                                     v
                          apa.shipping.scheduled
                                     |
                                     v
                        notification agent reacts (terminal)
```

Each agent is its own `@RegisterAiService` backed directly by `quarkus-langchain4j-ollama`
(never the Camel `langchain4j-agent` component), wired to one `@Tool` that does the agent's
domain work: write a durable row, increment a Micrometer counter, log a line, and (for order and
shipping) publish the next event. The chain uses only cataloged topics and events from
`_plans/domain-model.md`: `apa.orders.placed` (OrderPlaced) and `apa.shipping.scheduled`
(ShipmentScheduled). The notification agent's terminal effect is a `notifications.notifications`
row, not a new event: the domain model already states notification-service "reacts to every
event, emits none".

## Topology: one Quarkus app, three independently-wired agents

The task offered two shapes: three separate Quarkus apps/images in one compose, or one app
exposing three independently-wired agent routes/beans that still communicate only through Kafka.
This example takes the lighter shape, one Quarkus app, for three reasons:

1. **Resource budget.** Three separate images each pull in their own Ollama chat-model calls at
   the live `qwen3:4b-instruct-2507-q4_K_M` model; `OLLAMA_MAX_LOADED_MODELS=1` already caps the
   model server to one loaded model, so three app containers competing for the same Ollama
   instance buys nothing extra for a 5x heavier compose stack (three JVMs, three healthchecks,
   three build stages) over one.
2. **The point is the choreography, not the deployment topology.** "No orchestrator" is a
   statement about *who calls whom*, not about *how many JVMs run*. Splitting into three images
   would spend complexity proving a process-boundary fact (separate deployability) this chapter
   is not making, at the cost of the resource budget above.
3. **The discipline is enforced the same way either way.** Nothing in `order`, `shipping` or
   `notification` imports, injects or calls a class from another one of the three packages.
   Every cross-package fact flows through a Kafka topic: `order` never knows `shipping` exists,
   `shipping` never knows `notification` exists, and nothing in this module ever calls two of the
   three agents in sequence from one method. Three images would enforce the same rule by a
   classloader/network boundary instead of a code-review rule; the architectural point proven is
   identical. A reviewer reading `order/`, `shipping/` and `notification/` in turn sees the same
   "no orchestrator" shape a three-image compose would show, in one sitting instead of three
   READMEs.

If a later chapter needs to show the same choreography split across real process boundaries (for
example to make a rolling restart of one agent visible without the other two restarting), that is
a new example, not a retrofit of this one: CLAUDE.md's "fixed domain" and "no speculative
infrastructure" rules both argue against building that boundary here before anything in the book
asks for it.

## MODEL=off: the fixture lane

Each agent's own `*.agent.model-source` config property picks the decision path. `ollama` (the
default, and what compose and the live `verify.sh` always use) lets the real model decide and
call that agent's tool itself. `fixture` (forced by the `%test` profile, so `mvn -B verify` and
CI never need a model) skips the model:

- **order** and **notification** have no branch to decide (placing the order and sending the
  notification are the one deterministic action either way), so their `*FixtureAdvisor` just
  calls the tool directly.
- **shipping** chooses a carrier, so its `ShippingFixtureAdvisor` replays a recorded decision
  from the committed `src/main/resources/fixtures/shipment-decisions.jsonl`, falling back to the
  same amount-based rule `ShippingAdvisor`'s system prompt states (AIRCO at 100.00 or more,
  otherwise FASTLINE) for any order id the fixture does not name, mirroring
  17-event-triggered-agent-wakeup's record/replay discipline.

Either path ends at the same `@Tool` method, so the durable record, the counter, the log line
and (for order and shipping) the published event this chapter's checks assert on are identical
whichever path produced them.

## The negative witness (not run by this script)

`choreography.drop-shipment-scheduled` (`CHOREOGRAPHY_DROP_SHIPMENT_SCHEDULED` in compose), demo
code, guards a Camel `filter()` in `ShippingEventRoutes` that silently drops every
`ShipmentScheduled` event instead of publishing it when enabled. This cuts the choreography at
exactly one link: the order agent and the shipping agent are untouched (the order is still
recorded, the shipment is still scheduled and written to `shipping.shipments`), but nothing ever
reaches `apa.shipping.scheduled`, so the notification agent's Kafka consumer never sees anything
for that order and no notification is ever recorded. The full steps `verify.sh` leaves for the
orchestrator to run live are in that script's trailing comment; this example only wires the
capability (the config property, the compose env var, the filter itself), proof that the break
isolates to one hop is a live, three-agent run this script does not perform.

## Run

```bash
../../scripts/ollama-model.sh check   # or pull, the first time
./verify.sh
```

`verify.sh` needs Docker Engine with the compose plugin; it builds the service and the shared
domain model inside the image, starts Kafka, PostgreSQL, Apicurio, Keycloak, Ollama and LGTM plus
the service, places one order, polls every hop (order record, shipment record, notification
record) and the OTel signals, and removes everything with `down -v`. Set `KEEP=1` to leave the
stack running for inspection.

Run the tests alone (Quarkus Dev Services starts Kafka and PostgreSQL containers; no compose
stack and no Ollama needed, since the `%test` profile forces every agent's fixture decision
path). Install the shared domain model first:

```bash
(cd ../domain-model && mvn -B -q install)
mvn -B verify
```

## Tests and checks in detail

Dev Services, MODEL=off throughout:

- `OrderIntakeTest`: HTTP validation (a body that is not valid JSON, or fails the `Order`
  constructor checks, is rejected with 400 and never reaches Kafka), and that a valid order is
  recorded and published keyed by its id.
- `ShippingAgentTest`: produces `OrderPlaced` directly onto `apa.orders.placed` (bypassing the
  order agent, to exercise the shipping agent in isolation) and asserts the fixture-recorded
  carrier wins over the rule, the rule falls back correctly in both directions, the published
  `ShipmentScheduled` event carries `customerId` forward, and an order never published leaves no
  shipment row.
- `NotificationAgentTest`: produces `ShipmentScheduled` directly onto `apa.shipping.scheduled`
  (bypassing the shipping agent) and asserts the notification is recorded, and an event never
  published leaves no notification row.
- `ChoreographyEndToEndTest`: one `POST /orders` call, in process, and polls all three hops in
  turn (`orders.orders`, `shipping.shipments`, `notifications.notifications`), proving the three
  agents actually cooperate through Kafka with nothing in this module calling two of them in
  sequence.

`verify.sh` adds the live, real-model, container-level view: the same three-hop round trip
through the real `OrderAdvisor`, `ShippingAdvisor` and `NotificationAdvisor` over Ollama, and OTel
signals reaching Tempo, Prometheus and Loki for all three agents.

## Layout

| Path | Purpose |
|------|---------|
| `src/main/java/.../order/` | the order agent: HTTP intake, the `@RegisterAiService`, the one tool, the publishing route |
| `src/main/java/.../shipping/` | the shipping agent: the Kafka consumer trigger, the `@RegisterAiService`, the fixture lane, the one tool, the publishing route and its negative-witness drop guard |
| `src/main/java/.../notification/` | the notification agent: the Kafka consumer trigger, the `@RegisterAiService`, the fixture lane, the one tool (no publishing: terminal) |
| `src/main/java/.../events/ShipmentScheduledEvent.java` | the per-example event payload record (not domain-model) that carries `customerId` across the shipping-to-notification hop |
| `src/main/resources/fixtures/shipment-decisions.jsonl` | the committed, recorded decisions `ShippingFixtureAdvisor` replays |
| `src/main/resources/db/migration/` | Flyway migrations for `orders.orders`, `shipping.shipments`, `notifications.notifications` |
| `compose.yaml` | includes the shared infrastructure and adds the one service |
| `Containerfile` | UBI 10 multi-stage build; context is `examples/` |
| `verify.sh` | the checks above; the negative witness is documented, not run, in its trailing comment |
