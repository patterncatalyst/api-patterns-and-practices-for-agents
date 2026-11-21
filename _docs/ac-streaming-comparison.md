---
title: "Streaming comparison: WebSockets, webhooks, and gRPC"
order: 41
part: "Appendices"
description: "Five transports for an agent-facing surface, compared on latency, durability and replay, fan-out, ordering, back-pressure, and whether an agent can reasonably consume each through a tool surface."
duration: "20 minutes"
---

## Learning objectives

- Compare request/response, server push, bidirectional streaming, webhooks, and a durable event log on the dimensions that decide a tool-surface design.
- State the situation each transport fits, instead of defaulting to whatever protocol a service already happens to speak.
- Apply reshape, not wrap, to a transport an agent should never touch directly, naming the facade that turns it into a tool call or a durable event.
- Separate what this catalog builds and verifies from what this appendix only compares.

## Why this matters

An agent-facing surface inherits whatever transport the service behind it speaks, and each transport answers a different question well: how fast an answer arrives, whether it survives a restart, whether more than one consumer can see it, and whether arrival order means anything. Picking a transport because a tutorial used it, or because the backing service already exposed it, settles none of those questions.

An agent is specifically bad at carrying the weight some transports expect of a caller: holding a socket open, reconnecting on schedule, deduplicating a redelivered callback. A transport choice for an agent-facing API decides how much of that weight a facade absorbs, versus how much lands on the agent itself, which has no session state to carry it in.

{% include excalidraw.html file="ac-streaming-comparison" alt="A decision quadrant placing five transports on two axes, whether the caller pulls on demand or the server pushes continuously, and whether the data is ephemeral or durable and replayable; a raw WebSocket or gRPC stream and a raw webhook callback both point toward the one shape an agent should receive, an MCP tool call or a durable event" caption="Figure C.1: The axes that place a transport, and the shape an agent gets" %}

## Five shapes, one question each

### Request/response: REST, an MCP tool call

A caller asks, and waits for one answer in the same round trip. Latency is bounded by the call itself; the only trade-off is how long the handler takes. Nothing about the call survives past its own response: a caller that disconnects before the answer arrives has no record to come back to, only its own retry. Fan-out does not exist here; a second caller asking the same question pays its own cost. Ordering follows whatever order the caller issued calls in, with no guarantee across callers. Back-pressure is just the handler's capacity: a caller that asks faster than it can answer waits longer, or times out.

This is the shape an agent already speaks. An MCP tool call is a request/response exchange with a typed input and a typed result; nothing about consuming it asks an agent to manage a connection, a subscription, or a redelivery.

### Server push: SSE, MCP Streamable HTTP

A caller opens one connection and the server writes to it as events happen, instead of the caller asking again on a schedule. Push narrows the gap between an event happening and a caller finding out, without paying for an empty poll. Server-Sent Events offers a resumption point through a last-seen event id; MCP's Streamable HTTP transport carries the same idea into a tool call sending progress notifications or multiple messages over one session before it resolves. Neither keeps a durable log of its own: a client gone long enough to fall outside the server's memory has lost what happened while away, unless the server persisted that history elsewhere. Fan-out means the server iterating its own list of open connections, with no broker between producer and consumer. Ordering holds within one connection, since it is one ordered stream. Back-pressure is a buffer the server keeps for a slow client, bounded by how much memory it will spend before dropping messages or closing the connection.

This is the surface an MCP Streamable HTTP server already gives a caller: a request that resolves to one result, with the option of intermediate messages over the same session, consumed without the caller ever touching a socket.

### Bidirectional streaming: WebSockets, gRPC streaming

Both sides can write at any time, over one long-lived connection, with no round trip per message once it is open. This is the lowest-latency shape of the five, and the one that costs the most to hold: an interrupted connection loses whatever was in flight, and neither protocol defines a replay story. Fan-out again means a connection registry the server keeps for itself. Ordering holds within one connection, never across several. gRPC streaming inherits HTTP/2 flow control, so a slow reader can signal the writer to pause; a plain WebSocket has none unless the application adds its own.

A caller that holds this connection open, reads frames off it, and decides when to reconnect has taken on exactly the protocol management a tool call exists to spare it. An agent has no natural place to keep that state between calls, so handing it a raw stream leaves the stream unmanaged, or its management logic bolted onto the agent's own calling code.

### Webhooks: server-to-server callbacks

A server calls a URL the receiver registered in advance, the moment an event happens, instead of the receiver asking. The push is near-instant from the sender's side, but it depends on the receiving endpoint being reachable at that moment; if not, the only recovery is whatever retry-with-backoff policy the sender built, and once that policy gives up, the event is gone unless the sender kept its own durable log. Fan-out means the sender managing a list of subscriber URLs and firing each independently. Ordering carries no guarantee: concurrent retries, out-of-order delivery, and duplicates under an at-least-once policy are the receiver's problem, not something the protocol prevents. There is no back-pressure signal a receiver can send back; a receiver that falls behind either drops inbound requests or lets the sender's retry queue grow.

An inbound HTTP callback is not a request an agent can wait on: an agent cannot "receive" a webhook the way it answers a tool call. A webhook belongs in front of a receiving service that turns the callback into something an agent can react to, typically by writing it to a durable store or republishing it as an event the agent's own consumer wakes on.

### A durable event log: Kafka

A producer writes to a topic and a consumer reads from it on its own schedule; the two are decoupled, so there is no round trip to measure, only how soon a live consumer happens to be reading, typically well under a second once a consumer group is up. The defining property is durability: a topic retains what it is configured to retain, and any consumer group, including one that did not exist when an event was produced, can replay from an offset. Fan-out is native: many consumer groups read the same topic independently, each at its own pace, with no coordination between them. Ordering is guaranteed per partition, keyed, never promised across an entire topic. There is no push-based back-pressure at the broker by design; a consumer pulls at its own rate, so a slow consumer falls behind without the producer ever finding out.

This is the asynchronous spine the event-triggered agent wakeup pattern and the consuming the async side pattern both build on: a consumer wakes an agent's own call, and the tool call that follows, not the consumer, is what a caller or a check observes.

## Choosing among the five

| Transport | Latency | Durability and replay | Fan-out | Ordering | Back-pressure | Agent-consumability |
|---|---|---|---|---|---|---|
| REST / MCP tool call | One bounded round trip | None; the caller's own retry is the only recovery | None; each caller pays its own cost | Per caller only | Handler capacity; a slow handler just makes the caller wait | Direct: the native shape of a tool call |
| SSE / MCP Streamable HTTP | Push, as the server has something to send | Resumable within a session; not a log | Server-managed connection list | Per connection | Bounded server buffer per client | Direct: the push MCP's own transport already provides |
| WebSockets / gRPC streaming | Lowest, full duplex, no per-message round trip | None; an interrupted connection loses what was in flight | Server-managed connection list | Per connection | gRPC: HTTP/2 flow control; plain WebSocket: none built in | Poor directly; needs a facade |
| Webhook callback | Near-instant if the receiver is up | Sender's retry policy only, then gone | Sender manages a subscriber list | None guaranteed | None; a slow receiver drops requests or backs up the sender's queue | Poor directly; needs a receiving service |
| Kafka (durable event log) | Decoupled; consumer-dependent, typically sub-second | Durable, replayable from any offset | Native; independent consumer groups | Per partition, keyed | Pull-based; a slow consumer only falls behind | Direct, through a consumer that wakes a tool call |

Pick request/response when a caller is already waiting in the same call for the answer and the handler finishes inside that round trip; this is the default for a read or a short write, and the shape nearly every MCP tool call takes in this catalog. Pick a server-push stream for an answer that arrives in pieces, a long model response, or progress on an operation the caller has to stay connected for just long enough to receive, not as a general-purpose channel an agent manages on its own. Pick a durable event log whenever an agent needs to react to a fact that happened elsewhere, on its own schedule, with a record that outlives the moment; choose it over a poll loop whenever the caller is not holding a request open for the answer. Reserve bidirectional streaming for the connection a facade owns between two services it controls, never as the interface an agent calls directly. Reserve a webhook for a caller that already runs a stable, publicly reachable receiving service, built to tolerate the sender's retry and deduplication behavior, and treat that receiving service, not the agent, as what the webhook talks to.

## Reshape, not wrap

A gRPC stream and a raw WebSocket connection are both shapes a service exposes for its own convenience, not a shape built with an agent caller in mind. Handing either one to an agent unchanged asks the agent to do what a human developer's client library usually does instead: open the connection, read frames in a loop, decide when a partial result is a complete one, and close the connection when it is done. None of that is what a tool call is for.

The reshape-not-wrap pattern elsewhere in this catalog makes the concrete version of this argument for a unary gRPC call: a separate payment service speaks gRPC only, and an MCP tool facade reshapes every value crossing the boundary, a proto3 integer amount back to a decimal string, a gRPC status code back to an instructive message, before any of it reaches a caller. The same argument extends to a stream. A gRPC server-streaming or bidirectional method behind that same reshaping facade does not get exposed as a second stream on the agent-facing side; the facade either buffers it into one bounded tool result once the stream completes, or it writes each item the stream produces to a durable store and lets a consumer on that store wake an agent's own call, the same shape the event-triggered agent wakeup pattern already uses for a Kafka topic. Either way, what an agent receives is a tool call it can finish in one call and a durable record it can come back to, never a handle to a connection it would have to keep open and tend.

The same move applies to a webhook. A receiving service terminates the inbound callback, deduplicates it against whatever the sender's at-least-once delivery policy requires, and republishes what happened as a domain event on a topic an agent's own consumer wakes on, the same conflict-guarded write the consuming the async side pattern uses for a redelivered event. The webhook is absorbed one hop before it ever reaches anything an agent calls.

## What this book builds, and what it only compares

This catalog builds and verifies two of the five shapes in this appendix as runnable examples: a durable Kafka event log, consumed by a Camel route that wakes an agent's own decision call, and quarkus-mcp-server's Streamable HTTP transport, which every MCP-packaged tool call in this catalog runs over. The one gRPC service this catalog runs sits entirely behind a reshaping facade, reachable only inside its own compose network, never exposed to an agent as a stream or a raw channel of its own.

Webhooks, a bidirectional WebSocket, and gRPC streaming exposed on its own are not built here. This appendix compares them on the dimensions above so a reader can reason about a service that already speaks one of them, without this catalog claiming to have proven an agent-facing pattern for a shape it has not shipped and verified.

## Anti-patterns

- **A raw stream handed straight to an agent.** Exposing a WebSocket or a gRPC streaming method as the interface an agent calls moves connection management, reconnection, and partial-result handling onto the one caller least equipped to do any of it.
- **A webhook aimed at an agent with no receiving service in front of it.** An agent cannot answer an inbound callback the way it answers a tool call; without a receiving service to absorb retries and duplicates, the sender's own at-least-once delivery behavior reaches the agent unfiltered.
- **Treating a server-push stream as a durable channel.** A client that is offline during a chunk of an SSE or Streamable HTTP session has lost that chunk unless the server persisted it somewhere else; neither transport is a log, whatever it resembles while the connection is open.
- **Choosing a durable event log out of habit for a call that needed an answer in the same round trip.** Routing a request that already has a waiting caller through a topic it must then poll back for its own result adds a hop that buys nothing and reproduces the poll loop the event-triggered agent wakeup pattern exists to remove.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
- WHATWG HTML Living Standard, Server-Sent Events: [html.spec.whatwg.org/multipage/server-sent-events.html](https://html.spec.whatwg.org/multipage/server-sent-events.html)
- RFC 6455: The WebSocket Protocol: [rfc-editor.org/rfc/rfc6455](https://www.rfc-editor.org/rfc/rfc6455.html)
- gRPC documentation, streaming RPCs: [grpc.io/docs/what-is-grpc/core-concepts](https://grpc.io/docs/what-is-grpc/core-concepts/)
- Apache Kafka documentation: [kafka.apache.org/documentation](https://kafka.apache.org/documentation/)
- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
