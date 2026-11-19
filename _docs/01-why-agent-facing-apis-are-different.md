---
title: "Why agent-facing APIs are different"
order: 1
part: "Foundations"
description: "Designing an API for an agent or a large language model is a systematically different engineering job from designing one for a human or a browser, measured on one real task against two contracts."
duration: "25 minutes"
---

## Learning objectives

- Name the five pressures that make an agent-facing contract a different engineering job than a human-facing one.
- Read a tool's name, description and argument schema as the agent's only per-call instruction set.
- Measure tool-call count, argument errors and token cost across a granular contract and an intent-shaped one for the same task.
- Recognize a bad, CRUD-shaped tool contract as the negative witness for the five pressures.

## Why this matters

An agent does not read documentation once and remember it. It reads a tool's
name, its description and its argument schema at the moment it decides
whether to call that tool, every single time, inside a window that also holds
the conversation, the system prompt and every earlier tool result. It cannot
ask a clarifying question before it acts, and once it acts, the only thing it
learns from is the text that comes back. Those three facts, read together,
make designing an API for an agent or a large language model a systematically
different engineering job from designing one for a human or a browser, not a
smaller version of the same job. The difference is regular enough to teach as
a repeatable set of design moves, each answering one of the same five
pressures.

## The contract is the instruction set

A human developer reads a guide once, forms a mental model, and calls an
endpoint from memory for months afterward. An agent has no persistent memory
of an API across calls unless something puts it back in context. Every call
it makes is decided fresh, from whatever the tool's definition says at that
moment. A vague tool name, an ambiguous parameter, or a description that
assumes outside knowledge does not slow a human down for long; it produces a
wrong call from an agent, immediately, because the definition is the only
instruction the agent ever gets. A required field, an enum of allowed values
and an unambiguous name do the work that a paragraph of prose does for a
human reader: they narrow what the caller can even attempt.

## A bounded context window

An agent's context window holds the system prompt, the conversation so far,
every tool definition registered for the session and every result returned
by an earlier call, all at once, all competing for the same bounded space. A
REST API with forty granular, CRUD-shaped endpoints is a reasonable surface
for a human who picks one endpoint from a search bar. Registered as forty
separate tools, the same surface crowds out everything else an agent is
holding, and the agent has to pick the right one from forty similar names
with no room left to reason about what it is doing. A handful of high-impact,
intent-named tools cost less context and get the right one picked more
often: a `search_orders` tool that resolves a filter and a limit internally
beats a bare `list_orders` an agent still has to paginate by hand, and a
single `cancel_and_refund` tool beats three granular calls an agent has to
sequence correctly on its own.

## Token economics

Every tool definition registered for a session is tokens spent before the
agent has done anything, and every field a response returns is tokens spent
on top of that, for every call. An internal identifier that means nothing to
a human reader, returned because a database row happens to have it, still
costs the same tokens as a field the agent actually needs, and if the agent
has to look the value up again later it costs a second call to translate it
back into something meaningful. Returning a human-readable order id instead
of a surrogate key, dropping fields nothing downstream reads, and letting a
caller ask for a smaller or larger response shape all lower the price of
every single call an agent makes against an endpoint.

## Self-correction from the error text alone

A human who gets a confusing error can open a browser tab, search for the
status code, or ask a colleague. An agent has none of that. The error
response is the entire signal it gets back, and the only thing it can use to
decide what to try next. A bare status code and an opaque error token tell an
agent that something failed and nothing about why, so it either repeats the
same failing call, guesses at a fix with no basis for the guess, or gives up
on a task a correctly phrased error would have let it finish. An error that
names the field that was wrong, states what a valid value looks like and
says what to do next turns a dead end into a correction the agent can act on
immediately.

## No human in the loop

A browser-driven workflow has a person who notices a double click, who waits
for a spinner before clicking again, and who reads an ambiguous confirmation
dialog before deciding. An agent calling an API in a loop has none of that
judgment built in by default: a retried call can double-submit an order, and
an operation that depends on information the caller did not provide gets
either a guess or a stalled task. Idempotency keys make a retried call safe
to repeat. Resolving ambiguity inside the backend, rather than asking the
caller to supply something it cannot reliably know, keeps a task moving
instead of stalling it. And for anything that should not complete the moment
an agent decides to attempt it, from issuing a refund to deleting a resource,
an explicit asynchronous step with a human-in-the-loop gate replaces the
judgment a person would otherwise have supplied.

## What this buys, measured

Two outcomes make these five pressures worth treating as engineering
requirements rather than style preferences: how often an agent picks the
right tool for a task, and how much of its reasoning budget a contract
consumes before the agent has accomplished anything. Both are measurable, and
a contract that ignores the five pressures above measurably loses on both.

A recorded run makes the contrast concrete. The same task, "cancel order
O-1007 and refund the customer," was given to the same local model through
the same `AiService`, once against a bad contract of three granular,
CRUD-shaped tools (`getOrder`, `updateOrderStatus`, `createRefund`), and once
against a single intent tool that does the whole job
(`cancelAndRefund(orderId, reason)`). Every failure on the bad contract was a
bare opaque code, with nothing explaining what was wrong or what to try
instead.

{% include excalidraw.html file="01-contract-contrast" alt="The bad granular contract costs five tool calls, two argument errors and 3185 tokens and never completes the refund; the good intent tool costs one call and 759 tokens and completes it" caption="Figure 1.1: Same task, same model, two contracts" %}

| | Good contract | Bad contract |
|---|---|---|
| Tool calls | 1 | 5 |
| Argument errors | 0 | 2 |
| Tokens (input plus output) | 759 | 3185 |
| Refund actually completed | yes | no |

The bad contract's run is not just slower. The agent called
`updateOrderStatus`, guessed a refund amount, got an opaque error, called
`getOrder` to find the real amount, called `createRefund` again without
required data, got a second opaque error, and gave up: the order ended up
cancelled, and the customer was never refunded. The good contract's single
call succeeded cleanly on the first attempt. The bad contract is the negative
witness here: it shows what an agent does when the contract gives it no way
to self-correct, not a strawman built to lose.

Treat every later pattern as one of these five pressures made concrete in
Quarkus, Camel or an MCP tool definition. A tool surface built this way is
also a smaller trust boundary: fewer, well-scoped tools give private data,
untrusted content and a path for that data to leave less room to combine
into the exfiltration risk named the lethal trifecta. The same five
pressures apply again once the
shipping domain's services are joined into agent-facing data products
instead of being called one at a time.

## Build, run, observe

```bash
./verify.sh                 # replays the recorded transcript; no model, no containers
LIVE=1 ./verify.sh          # runs the same task against the real local model instead
```

The replay asserts the numbers above directly from a committed transcript, so
nothing here needs a model or a container to check; `LIVE=1` reproduces the
same numbers against the running model. It ends at 6/6.

Try:

- Run `LIVE=1 ./verify.sh` and compare the live numbers to the replayed ones.
- Edit the bad contract's recorded transcript so it reports one tool call
  instead of five, then rerun: the contrast, and the rule built on it, stop
  holding.

## Anti-patterns

- **Granular, CRUD-shaped tools an agent must sequence itself.** `getOrder`, `updateOrderStatus` and `createRefund` as three separate calls, instead of one tool named for the operation.
- **Opaque errors with no remedy text.** A bare status code tells an agent something failed and nothing about what to try next, so it repeats the call or guesses.
- **Unguarded retries on a write.** An agent that retries after a timeout, against an endpoint with no idempotency guard, risks double-submitting the same operation.

## References

- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
- Anthropic, *Effective context engineering for AI agents*: [anthropic.com/engineering/effective-context-engineering-for-ai-agents](https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents)
- Anthropic, *Tool use with Claude*: [docs.anthropic.com/en/docs/build-with-claude/tool-use/overview](https://docs.anthropic.com/en/docs/build-with-claude/tool-use/overview)
- Simon Willison, *The lethal trifecta for AI agents*, 2025: [simonwillison.net/2025/Jun/16/the-lethal-trifecta](https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/)
- RFC 9457: Problem Details for HTTP APIs
- JJ Geewax, *API Design Patterns*, Manning, 2021
