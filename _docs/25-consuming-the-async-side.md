---
title: "Consuming the Async Side"
order: 25
part: "Consume"
description: "A Kafka consumer wakes a composing agent the instant a shipment is delivered, and the structured record of what happened comes from the event, never from the agent's composed reply."
duration: "25 minutes"
---

## Learning objectives

- Wake a composing agent on a Kafka event instead of a dashboard or service polling the same topic for the same fact.
- Keep the structured fields a durable record is asserted on sourced from the consumed event, never from the agent's own composed reply.
- Guard the one side effect so a redelivered event writes at most once, under a key the event itself supplies.
- Recognize a hidden poll loop, a disabled consumer that looks healthy from outside, and a write with no conflict guard as the ways this pattern fails silently.

## Why this matters

A service that needs to notify a customer the moment their shipment is delivered has the same two options as any agent waiting on a fact: ask repeatedly, or be told. Standing up a notification service that polls a shipment-status table or topic for a change it does not yet know happened pays the same cost every polling consumer pays, a schedule that is either too eager or too late, and it adds a second problem on top: at-least-once delivery means a real event can arrive more than once, and a notification sent twice for one delivery is worse than one sent late. A Kafka consumer solves the first problem by waking only when the event actually happens; it only solves the second if the write that follows is built to tolerate a repeat.

{% include excalidraw.html file="25-consuming-the-async-side" alt="A disabled consumer leaves a delivered event sitting unconsumed on its topic, never lost; the active consumer wakes a composing agent, builds the durable record from the event's own fields and writes it under a conflict guard keyed on order id and event type, so a redelivered event adds no second row" caption="Figure 25.1: The event wakes the agent; the record it writes is keyed so a repeat of the same event is a safe no-op" %}

## Pattern: Consuming the Async Side

### The problem

A service that reacts to a published event by composing a reply and writing a record has two separate things that can go wrong independently: it might never find out the event happened at all, whether because it is polling instead of consuming or because its consumer is stopped without anyone noticing, and it might act on the same event twice, because at-least-once delivery guarantees a message arrives at least once, never exactly once. Solving only one of these leaves the other to surface later: a customer who gets two delivery emails, or a customer who never gets one because a consumer was quietly down.

### The solution

**A Camel Kafka consumer wakes a composing agent the instant a domain event is published, and the durable record that follows is written from the event's own fields under a key that makes a redelivery of the same event a safe no-op.**

### How Quarkus and Camel model it

`ShipmentDeliveredRoute` is the trigger, nothing more:

{% raw %}
```java
from("kafka:" + Topics.SHIPPING_DELIVERED + "?groupId={{consumer.async.group-id}}")
        .routeId("shipment-delivered-consumer")
        .autoStartup(consumerEnabled)
        .unmarshal().json(ShipmentDelivered.class)
        .process(exchange -> notificationBean.notify(exchange.getIn().getBody(ShipmentDelivered.class)));
```
{% endraw %}

`consumer.async.enabled` (demo code) stops the route outright when set to false; a Kafka consumer group never loses a message by being stopped, it only delays reading one, so an event published while the route is down sits at its committed offset until the route resumes. `NotificationBean` is the thin bridge a Kafka route needs to call a request-scoped langchain4j service, activating a request context the route's worker thread does not otherwise have. `NotificationComposerAgent`, a local langchain4j `AiService` backed directly by `quarkus-langchain4j-ollama`, carries no tool at all; its only job is to turn the event into a sentence or two a customer can read:

```java
String message = "fixture".equals(modelSource)
        ? fixtureComposer.compose(event)
        : agent.compose("Order " + event.orderId() + " was delivered via " + event.carrier()
                + ", tracking number " + event.trackingNumber() + ".");
Notification notification = new Notification(
        UUID.randomUUID().toString(), event.orderId(), event.customerId(), SHIPMENT_DELIVERED_EVENT_TYPE);
recorder.record(notification, message);
```

The order id, the customer id and the event type that make up `Notification` come straight from the consumed `ShipmentDelivered` event, built by this method before the agent is ever called; only the message text passes through the model. `NotificationRecorder` writes the row with a conflict guard keyed on exactly the fields a redelivery repeats:

```java
"INSERT INTO notify.notifications (id, order_id, event_type, customer_id, message) "
        + "VALUES (?, ?, ?, ?, ?) ON CONFLICT (order_id, event_type) DO NOTHING"
```

`(order_id, event_type)` is the same pair that identifies the event itself, so the same `ShipmentDelivered` event delivered twice, whether from a rebalance or a retrying producer, resolves to the same key and the second insert is a no-op; the row that exists is whichever delivery's composed message got there first, and which one that was is left unasserted, because the model's wording is nondeterministic and the checks have nothing to gain by pinning it down. A fixture path, forced in tests, composes a deterministic templated message instead of calling a model, reaching the identical `record` call, so the row, the counter and the log line a check reads are indistinguishable whichever path produced them.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent react to a domain event the instant it is published, composing a reply without ever letting that composed reply decide what gets durably recorded.
> **Contract shape:** operation intent "a shipment was delivered, notify the customer and record that it happened"; inputs the event's own payload (order id, customer id, carrier, tracking number); response shape a durable record of the event, keyed so a repeat of the same event performs no second write; error shape silence (the event stays unconsumed) rather than a thrown error when the consumer is stopped; idempotency key the event's own identity, order id and event type together, since a redelivered event should resolve to the same row, not a second one.
> **Build it here:** "Using `ShipmentDeliveredRoute` and `NotificationRecorder` as the model, add a second event-triggered notification on a different topic in this service, composing its own message and writing its own durable, conflict-guarded row."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI with aiokafka: a consumer that reacts to a published event by composing a reply and writing a durable record under a unique key derived from the event's own identity, so a redelivered event is a safe no-op."
> Check: a redelivery of the same event never adds a second record, and a stopped consumer leaves the event exactly where it fell until it resumes.

## Build, run, observe

```bash
./verify.sh
```

Run from the consuming-the-async-side example, this publishes a shipment-delivered event and confirms the woken agent records a notification keyed on the event's own order id and customer id, confirms the trace, metric and log line all reach the observability stack, republishes the same event and confirms the row count stays at one, then stops the consumer, confirms a delivered event sits unconsumed, and confirms it is read once the consumer resumes. It ends at 16/16.

Try:

- Set `CONSUMER_ASYNC_ENABLED=false` and leave it set: every later shipment-delivered event sits unconsumed indefinitely.
- Drop the conflict guard from the insert and watch a redelivered event add a second row instead of staying at one.
- Force the fixture message path and confirm the identical row, counter and log line appear, with the model never asked to compose anything.

## Anti-patterns

- **A dashboard or service polling for the same fact a topic already publishes.** Shortening the poll interval only trades cost for staleness along the same curve; it never removes the trade-off a consumer on the topic has no need to make.
- **A write with no conflict guard keyed on the event's own identity.** Without a unique constraint the insert enforces, an at-least-once redelivery becomes an at-least-once notification, and a customer receives the same message more than once for work that happened exactly once.
- **A disabled consumer that looks the same as a healthy one from outside.** `autoStartup(consumerEnabled)` stops the route, not the service; a health check against the application as a whole, rather than the specific route's own running state, reports healthy while every event on the topic sits unconsumed.

## When not to use it

A caller already waiting in the same request for an answer gets nothing from this pattern: there is no one to wake, since the caller supplied the trigger itself and needs the reply in the same round trip, so a direct call stays a direct call rather than being rerouted through a topic it would then have to consume its own answer back from. A notification whose loss is tolerable, one more retry away from fine either way, does not need the idempotency guard's engineering cost either; the guard matters specifically when a duplicate send is worse than a late one.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Apache Camel documentation, Kafka component: [camel.apache.org/docs](https://camel.apache.org/docs/)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
