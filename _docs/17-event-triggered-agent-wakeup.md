---
title: "Event-Triggered Agent Wakeup"
order: 17
part: "Build: asynchronous APIs"
description: "A Kafka event wakes an agent to act on its own schedule, instead of the agent polling an endpoint to find out whether anything changed."
duration: "20 minutes"
---

## Learning objectives

- Wake an agent's own decision call on a Kafka event instead of polling an endpoint on a timer.
- Keep the consumer thin, handing the decision and its side effect to the agent's own call path.
- Record a durable, observable effect for the woken call, not just a log line, so the result survives independent of what the model's own wording says.
- Recognize a hidden poll loop, a model call blocking the consumer thread, and an unguarded redelivery as the ways this pattern fails silently.

## Why this matters

An agent that needs to know when a payment clears has two ways to find out: ask repeatedly, or be told. Asking repeatedly means picking an interval, and every interval is wrong in one of two directions: short enough to catch the change quickly wastes calls almost every time it asks, long enough to be cheap leaves the agent acting on stale information for however long the interval runs. A Kafka event removes the choice entirely: a consumer wakes the instant the event the agent cares about is published, with no schedule to tune and no empty poll to pay for.

{% include excalidraw.html file="17-event-triggered-wakeup" alt="A poll loop either sits idle on a timer or finds out late; the Camel Kafka consumer on apa.payments.processed wakes the agent's own call path the instant a payment is processed, and the tool call it makes is what the checks observe" caption="Figure 17.1: The event wakes the agent; the tool call it makes is what the checks observe" %}

## Pattern: Event-Triggered Agent Wakeup

### The problem

A poll loop couples an agent's reaction time to an interval someone picked in advance, with no way to be both prompt and cheap at once. Worse, the loop itself becomes a piece of infrastructure to run, monitor and scale independent of the work it exists to trigger, and every poll that finds nothing changed is a wasted round trip against whatever endpoint it asked.

### The solution

**A Kafka consumer wakes the agent's own decision call the instant a relevant event arrives, and the tool call that decision makes, not the consumer itself, is what performs the work and leaves the durable record a check can observe.**

### How Quarkus and Camel model it

A Camel route is the trigger, nothing more. `PaymentWakeupRoute` consumes `apa.payments.processed` and hands each record straight to a CDI bean:

{% raw %}
```java
from("kafka:" + Topics.PAYMENTS_PROCESSED + "?groupId={{wakeup.consumer.group-id}}")
        .routeId("payment-wakeup")
        .autoStartup(consumerEnabled)
        .unmarshal().json(Payment.class)
        .process(exchange -> wakeupBean.wake(exchange.getIn().getBody(Payment.class)));
```
{% endraw %}

`wakeup.consumer.enabled` (demo code) stops the route outright when set to false; a Kafka consumer group never loses a message by being stopped, it only delays reading one, so an event published while the route is down sits at its committed offset until the route resumes and reads it from where it left off. `AgentWakeupBean` is the thin bridge a Kafka route needs to call a request-scoped langchain4j service: a Camel Kafka consumer runs on a worker thread with no request context active, so the bean activates one for the call. `ShipmentAdvisor` is a local langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama`, never the Camel `langchain4j-agent` component: the Camel route's whole job is waking the call, not driving the model itself. The advisor's one tool, `ShipmentPrepTool.prepShipment`, is what a check actually observes:

```java
@Tool("Record that a shipment is ready to prepare for an order, with the chosen carrier (FASTLINE or AIRCO)")
@Transactional
public String prepShipment(String orderId, String carrier) {
    invocations.increment();
    LOG.infof("tool invoked: prepShipment orderId=%s carrier=%s", orderId, carrier);
    // writes shipping.shipment_prep, keyed by order id, in the same call
}
```

The write, the Micrometer counter and the log line all happen in the one method the model calls, so an absent database row is already conclusive proof the counter never incremented either; a point-in-time metric query is not, since a metrics backend can keep a previous container's last-exported sample live well past a restart. A fixture decision path (`agent.wakeup.model-source=fixture`) replays a committed, recorded carrier choice instead of calling the model, so a build pipeline never needs Ollama running; either path ends at the same tool, so the row, the counter and the log line a check reads are identical whichever path produced them.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent react to a domain event the instant it is published, with no caller polling an endpoint to find out whether anything changed.
> **Contract shape:** operation intent "a payment was processed, decide what to do about it"; inputs the event's own payload (order id, amount); response shape a durable record of the decision taken, not a value returned to a caller who is not waiting for one; error shape silence (the event stays unconsumed) rather than a thrown error when the consumer is stopped; idempotency key the event's own key, since a reprocessed event should resolve to the same effect.
> **Build it here:** "Using `PaymentWakeupRoute` and `ShipmentPrepTool` as the model, add a second Kafka-triggered wakeup on a different event in this service, reacting with its own tool and its own durable record."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI with aiokafka: a consumer loop that reacts to a published event by calling an agent's decision logic directly, writing a durable record of the outcome rather than returning a response to a waiting caller."
> Check: a disabled consumer leaves the event exactly where it fell, no record is ever written for it, and restoring the consumer is what makes the missed event's record finally appear.

## Build, run, observe

```bash
./verify.sh
```

Run from the event-triggered-agent-wakeup example, this publishes a payment-processed event, confirms the woken agent records a shipment prep decision in PostgreSQL, confirms the trace, metric and log line all reach the observability stack, then stops the consumer, confirms a second event sits unconsumed, and confirms it is read once the consumer resumes. It ends at 14/14.

Try:

- Set `WAKEUP_CONSUMER_ENABLED=false` and leave it set: every later payment-processed event sits unconsumed.
- Change the amount-based carrier rule in `ShipmentAdvisor`'s system message and watch which carrier gets recorded for an order near the threshold.
- Read `fixtures/wakeup-decisions.jsonl` and place an order id it names: the recorded carrier wins even when the rule would pick differently.

## Anti-patterns

- **A poll loop hiding behind a short interval.** Shortening the interval does not remove the trade-off, it only shifts where on the cost-versus-staleness curve the waste lands.
- **Model work running on the consumer thread.** Calling the model synchronously inside the Kafka poll loop ties the group's rebalance and lag behavior to however long that call takes; a slow or stuck model call stalls every partition the consumer owns, not just the one record it is working on.
- **No idempotency on a redelivered event.** A consumer that repeats a side effect on every redelivery, rather than writing a record keyed so a repeat is a no-op, turns an at-least-once delivery guarantee into an at-least-once execution bug.

## When not to use it

A caller that is already waiting in the same request for the answer gets nothing from this pattern: an event-triggered wakeup has no one to respond to, so a synchronous call that already carries the trigger and needs the answer in-band should stay a direct call, not be rerouted through a topic and a consumer it would then have to poll for its own result.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation, Kafka component: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
