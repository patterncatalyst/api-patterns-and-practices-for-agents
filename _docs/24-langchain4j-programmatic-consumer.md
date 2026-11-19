---
title: "LangChain4j Agent as a Programmatic Consumer"
order: 24
part: "Consume"
description: "An unattended langchain4j agent calls a remote MCP tool programmatically, and what gets recorded comes from the tool's own captured result, never from the model's retelling of it."
duration: "20 minutes"
---

## Learning objectives

- Register a remote MCP tool server on an unattended langchain4j `AiService` with `@McpToolBox`, instead of hand-rolling an HTTP client inside the agent's own code.
- Read the exact text a remote tool call returned from the framework's own captured result, rather than trust the model's restatement of it.
- Record a durable, observable consultation keyed on a value the model has no way to produce except by actually reaching the remote tool.
- Recognize recording the model's own prose, treating a written row as proof the remote call succeeded, and discarding the captured result for a bare reply as the ways this pattern fails silently.

## Why this matters

An agent that calls a remote tool to look something up and then answers in its own words leaves a gap between what happened and what got said. The tool call itself is deterministic: it reaches a server, the server answers, the bytes are what they are. The model's summary of that answer is not: a large language model is free to compress, misstate, or omit a field between reading a tool result and producing its own reply, and nothing about an ordinary agent method call tells a caller which of the two is about to become a durable record. A classification tolerates a little paraphrase. A shipment tracking number does not, because a check that reads the model's retelling is only as reliable as whatever the model happened to write, and nothing in a chat completion guarantees that it wrote the right thing.

{% include excalidraw.html file="24-captured-tool-result" alt="The model's own retelling of a tool result is nondeterministic and unverifiable; the framework's captured tool-execution result is read and parsed server side before any model prose touches it, and the recorded tracking number is a value the model has no way to produce except by actually reaching the remote tool" caption="Figure 24.1: The durable record comes from the captured tool result, never from the model's own retelling of it" %}

## Pattern: LangChain4j Agent as a Programmatic Consumer

### The problem

An agent that calls a remote tool to look something up and then answers in its own words leaves a gap between what happened and what got said, and nothing about an ordinary agent method call closes that gap on its own. If the only thing a consuming service captures is the model's final reply, the record it keeps traces back to a sentence the model chose to write, not to the remote system that actually answered.

### The solution

**A programmatic consumer reads the actual tool-execution result the framework captured, never the model's own restatement of it, so what gets recorded is traceable to the remote call that produced it, not to how the model chose to describe it.**

### How Quarkus and Camel model it

`OrderStatusConsumerAgent` is a local langchain4j `AiService`, backed directly by `quarkus-langchain4j-ollama`, run unattended with no human in the loop:

```java
@RegisterAiService
@ApplicationScoped
public interface OrderStatusConsumerAgent {

    @SystemMessage("""
            You are an order status consumer agent, run unattended with no human in the loop. When
            asked for an order's status, call the orderStatus tool exactly once with the order id,
            then report the status you got back.
            """)
    @McpToolBox("apa-orders")
    Result<String> consume(String message);
}
```

`@McpToolBox("apa-orders")` registers a packaged MCP tool server's tools, `orderStatus`, `paymentStatus` and `shipmentTracking`, as remote tools this one method may call over Streamable HTTP, configured entirely by a handful of properties; nothing here hand-rolls an HTTP client or parses a JSON-RPC envelope itself. The method returns `Result<String>`, not a bare `String`, specifically so the caller can read `toolExecutions()`: the exact text the remote `orderStatus` tool returned, captured by the framework the moment the call completed.

A thin `@ActivateRequestContext` bridge is what actually reads it:

```java
Result<String> result = agent.consume("Get the status of order " + orderId + ".");
String orderStatusResult = result.toolExecutions().stream()
        .filter(execution -> "orderStatus".equals(execution.request().name()))
        .map(ToolExecution::result)
        .reduce((first, last) -> last)
        .orElse("");
recorder.recordConsultation(orderId, orderStatusResult);
```

Filtering for the named tool call, rather than reading the method's own returned prose, is what makes the model's final sentence irrelevant to the record: `OrderStatusRecorder.recordConsultation` parses the order status and shipment tracking number out of that captured text, server side, before the row is written. A nondeterministic model cannot change what gets recorded as long as it makes the call; it also cannot embellish the record by inventing a plausible-looking field, because nothing downstream of the captured text ever reads the model's reply again. A `POST /consume` endpoint, a thin HTTP trigger and nothing more, is the only thing that starts the first call.

`ConsumerBean` always calls `recordConsultation` after calling the agent, whether the model reached the remote tool or not: if `toolExecutions()` comes back empty, the captured text is an empty string, parsing finds no JSON to read, and the recorder writes a row with status `UNKNOWN` and no tracking number at all. A row's presence is therefore not proof the round trip happened; only a value the remote tool alone could have supplied is. A fixture path, forced in tests so a build pipeline never needs a model running, calls the same remote `orderStatus` tool directly through a hand-built MCP client, bypassing the model entirely; it reaches the identical `recordConsultation` method, so the row, the counter and the log line a check reads are indistinguishable whichever path produced them.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** the API must let an agent consult another service's capability programmatically and record what that capability actually returned, not what the agent says it returned.
> **Contract shape:** operation intent "look up an order's status from a remote capability and record the result"; inputs the order id; response shape a durable consultation record keyed by order id, carrying the fields the remote call actually returned; error shape a consultation with no remote-derived value when the remote capability is unreachable, never a thrown error that blocks the record from being written at all; idempotency key the order id, since a repeat consultation for the same order safely overwrites with its latest state.
> **Build it here:** "Using `OrderStatusConsumerAgent` and `OrderStatusRecorder` as the model, add a second consultation path that calls the remote `shipmentTracking` tool and records its own durable row the same way, reading the captured result rather than the model's reply."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a programmatic agent client that calls a remote tool or endpoint, captures the raw response at the client boundary, and writes a durable record from that captured response rather than from any natural-language summary the agent produces."
> Check: a broken connection to the remote capability leaves the recorded consultation without any value only that capability could have supplied, however many times the consultation is repeated.

## Build, run, observe

```bash
./verify.sh
```

Run from the langchain4j-programmatic-consumer example, this consults a seeded order and confirms the recorded status and shipment tracking number both came from the remote tool's own result, confirms the trace, metric and log line reach the observability stack, then points the remote connection at a path nothing answers and confirms no row can ever carry the seeded tracking number, finally restoring the connection and confirming it consults correctly again. It ends at 15/15.

Try:

- Point the remote tool connection at the wrong path and watch the recorded tracking number stay absent no matter how many times you ask about the same order.
- Read the captured text `ConsumerBean` passes to the recorder, and compare it against whatever the model's own reply text says.
- Force the fixture decision path and confirm the identical row, counter and log line appear, with the model never asked to call anything.

## Anti-patterns

- **Recording the model's own reply instead of the captured tool result.** A row built from the method's returned prose carries whatever the model chose to say, which can compress, misstate or omit a field the remote tool actually returned.
- **Treating a written row as proof the remote call succeeded.** When the consumer always writes a row after calling the agent, row presence alone proves nothing; only a value the remote tool alone could supply distinguishes a real round trip from an empty one.
- **Discarding the captured result for a bare reply.** Changing a method's return type from a result that exposes its tool executions to a plain string throws away the one place the real tool output is still inspectable, leaving the model's prose as the only remaining source.

## When not to use it

A consultation whose answer feeds nothing but the next sentence in a conversation, with no durable record and no other system acting on the result, gets nothing from capturing the tool's raw output separately: there is no record for the capture to protect, so paraphrase risk is exactly as low as it would be for any other conversational reply. The pattern also adds nothing when a tool already returns a structured, strongly typed value a caller receives directly rather than a document a model would otherwise re-read and restate: a typed return value is not prose, so there is no retelling step to distrust in the first place.

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Quarkus LangChain4j documentation: [docs.quarkiverse.io/quarkus-langchain4j/dev/index.html](https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html)
- Apache Camel documentation, Platform HTTP component: [camel.apache.org/docs](https://camel.apache.org/docs/)
