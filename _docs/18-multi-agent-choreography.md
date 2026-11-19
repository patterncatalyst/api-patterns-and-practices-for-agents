---
title: "Multi-Agent Choreography"
order: 18
part: "Build: asynchronous APIs"
description: "Several agents coordinate through events on a shared topic, each reacting to what the others produced, instead of one agent orchestrating every step directly."
duration: "20 minutes"
---

## Learning objectives

- Coordinate several agents through events on shared topics, with no agent calling another directly.
- Give each agent exactly one reaction to make and one event to publish, so its own half of the chain reads in one sitting.
- Trace a multi-hop chain by the durable record each agent leaves, not by a call stack that never exists.
- Recognize a hidden orchestrator, a shared mutable back channel, and a missing correlation id as the ways choreography quietly becomes orchestration.

## Why this matters

Three agents that need to cooperate on one order, one that places it, one that ships it, one that tells the customer, can be wired two ways. One agent can call the other two directly, holding the whole sequence in its own method and deciding what happens next at every step. Or each agent can react only to an event the step before it produced, with nothing in any one agent's code naming or calling either of the other two. The first shape is an orchestrator wearing an agent's clothing: it still needs to know every downstream agent's interface, still breaks if one of them changes shape, and still has to be the one place that decides what runs next. The second shape, choreography, lets each agent own its own reaction and nothing else, coordinated entirely by what lands on a topic.

{% include excalidraw.html file="18-multi-agent-choreography" alt="Three agents coordinate only through events on shared topics, with no orchestrator; dropping the one link between shipping and notification stalls exactly that hop, leaving the earlier two agents' own work untouched" caption="Figure 18.1: Cutting one link stalls one hop; the agents before it finish their own work regardless" %}

## Pattern: Multi-Agent Choreography

### The problem

An orchestrator that calls agent B then agent C from inside agent A's own method knows about both of them by name, has to handle both of their failure modes in one place, and turns a three-way collaboration into a single point that must change whenever any of the three does. Nothing about that shape improves just because the three participants happen to be agents instead of services.

### The solution

**Each agent reacts only to an event the step before it produced and publishes only the event the step after it needs, with no agent importing, injecting or calling another agent's code directly.**

### How Quarkus and Camel model it

The order agent is the only entry point a caller ever reaches directly; from there, every later hop is a Kafka consumer reacting to a Kafka event. Its tool writes the order and publishes the next event in the same call:

```java
@Tool("Record a placed order and publish OrderPlaced for the shipping agent to react to")
@Transactional
public String placeOrder(...) {
    // writes orders.orders, then:
    producer.sendBody("direct:publishOrderPlaced", order);
}
```

The shipping agent never calls the order agent and never imports its package; a Camel Kafka consumer on `apa.orders.placed` is the only way it learns an order exists. Its own langchain4j `AiService`, backed directly by `quarkus-langchain4j-ollama`, chooses a carrier and calls `scheduleShipmentTool`, which writes the shipment and publishes `apa.shipping.scheduled` for the notification agent, the same choreography shape one hop further down the chain. The notification agent is terminal: its tool writes a notification record and publishes nothing further, matching the domain's own statement that the notification service reacts to every event and emits none. Each agent's own fixture-or-model decision path (a `model-source` property, same shape per agent) keeps a build pipeline from needing a live model, and each is tested in isolation by producing the event one hop upstream directly onto its topic, bypassing the agent that would normally have published it, so a test of the shipping agent never depends on the order agent having run first.

The only point where the whole chain is cut, by design, is a single Camel `filter()` guarding the shipping agent's publish step:

```java
from("direct:publishShipmentScheduled")
        .filter(exchange -> dropGuard.allowPublish())
        .setHeader(KafkaConstants.KEY, simple("${body.orderId}"))
        .marshal().json()
        .to("kafka:" + Topics.SHIPPING_SCHEDULED);
```

Closing that filter drops the event silently: the order is still recorded, the shipment is still scheduled and written, but nothing ever reaches `apa.shipping.scheduled`, so the notification agent's consumer sees nothing for that order and nothing is ever recorded downstream. Neither agent's consumer is touched; only the one link between them breaks, isolating the choreography's weak point to exactly the hop the filter guards.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let several agents cooperate on one business process with no agent calling another agent's interface directly.
> **Contract shape:** operation intent "react to the event this step produced, then publish the event the next step needs"; inputs the upstream event's payload; response shape a durable record plus, where there is a next step, exactly one published event; error shape silence downstream (an unpublished event leaves nothing for the next agent to react to) rather than a propagated exception across agents; idempotency key the upstream event's own key, carried forward into whatever this agent publishes.
> **Build it here:** "Using the shipping agent's wiring as the model, add a fourth agent that reacts to `apa.shipping.scheduled` the same way the notification agent does, but writes its own distinct record, with no import of either existing agent's package."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI with aiokafka: three independent consumer processes, each reacting only to one topic and publishing only to the next, with no process importing or calling another's code."
> Check: dropping the event between any two agents in the chain stalls exactly that hop, and every agent before the break still completes its own recorded effect.

## Build, run, observe

```bash
./verify.sh
```

Run from the multi-agent-choreography example, this places one order and polls every hop in turn: the order record, the shipment record with a valid carrier, and the notification record, confirming the full chain completes with no agent calling another directly. It ends at 18/18.

Try:

- Place an order with an amount at or above the carrier threshold and watch which carrier the shipping agent's fixture or rule picks.
- Set `CHOREOGRAPHY_DROP_SHIPMENT_SCHEDULED=true` and place an order: the order and shipment still record, but no notification ever appears.
- Produce a `ShipmentScheduled` event directly onto its topic, bypassing the shipping agent, and watch the notification agent react to it alone.

## Anti-patterns

- **A hidden orchestrator.** One agent calling another's interface, or issuing a request and waiting on its response, inside its own method is orchestration wearing choreography's name; the moment any agent's code names another agent, the independence this pattern depends on is gone.
- **A shared mutable table as a back channel.** Agents polling or writing to a table they all share, instead of reacting to events on their own topics, recreates tight coupling through the database instead of the API, with none of an event's ordering or replay guarantees.
- **No correlation id across the chain.** An event that does not carry the id the step before it used makes a multi-hop flow impossible to trace end to end; every event in this chain carries the order id forward for exactly that reason.

## When not to use it

A short, fixed sequence that one team already owns end to end gains little from being split into independently-reacting agents: a single, visible coordinator calling two or three steps in order is easier to read, test and change than the same sequence spread across topics and consumers that have to be traced instead of read. Choreography pays for its complexity when the steps are owned by different teams or need to evolve independently, not as a default shape for every multi-step flow.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation, Kafka component and the Message Filter EIP: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
