---
title: "How to read this catalog"
order: 2
part: "Foundations"
description: "The anatomy every pattern page shares, the forces every pattern trades against, the taxonomy that groups them, the field vocabulary they assume, and the principles and anti-patterns named once and invoked throughout."
duration: "20 minutes"
---

A pattern catalog only works if every entry answers the same questions in the
same order, so two entries can be compared on the same terms instead of read
as unrelated essays. What follows is the apparatus this catalog uses: the
form a pattern page takes, the forces a pattern resolves or trades away, the
taxonomy that groups the entries, the vocabulary the entries assume, and the
handful of principles and anti-patterns that get named here once rather than
re-explained on every page.

## The pattern form

A pattern page opens with a Name, the identifier used everywhere else that
entry is cited. A Problem section states the pressure that makes the default,
human-shaped design fail for an agent caller. A Solution states the rule in
one bold sentence, concrete enough to apply without reading further. How
Quarkus and Camel model it shows the actual classes, routes and annotations
that implement the rule, never a narration of the code line by line. Build,
run, observe gives the commands, names what to watch for, and ends with a
pass count from a runnable example; that example's own checks always include
a negative witness, a check that fails once the pattern's guard is removed,
so a reader can confirm the rule is actually doing something rather than
always passing. When not to use it states the trade-off directly: every
pattern here costs something, and the cases where that cost is not worth
paying are named on the page. References closes the page with sources and
related entries; nothing outside that section points sideways to another
page, so any entry can be read alone.

{% include excalidraw.html file="02-pattern-form" alt="The sections of one catalog entry in order: Name, Problem, Solution, How Quarkus and Camel model it, Build run observe ending in a negative witness, When not to use it, References, with an optional cross-stack callout" caption="Figure 2.1: The anatomy of one catalog entry" %}

Most entries add one cross-stack callout: a short, stack-neutral statement of
what the pattern requires, plus a prompt that builds it against this
catalog's own running example and a second prompt that restates the same
requirement for a different stack entirely. The callout exists because the
rule in the Solution sentence is never actually tied to Quarkus or Camel; the
callout is where that portability gets made explicit and actionable.

## The forces

Every pattern resolves or trades against the same seven forces, and naming
them here means no pattern page has to re-derive them.

- **Context budget**: the agent holds the API surface and every result inside one bounded window.
- **Token economy**: every tool definition and every returned field costs tokens in that window, on every call.
- **Tool-selection accuracy**: the agent picks a tool from its name and description alone, and a crowded or ambiguous surface degrades that pick.
- **Error legibility**: the agent recovers only from what an error's text says, never from a developer reading a stack trace.
- **Reasoning and execution boundary**: a model reasons about what to do; the application decides whether that action actually happens.
- **Trust boundary**: private data, untrusted content and a path for that data to leave are three separate hazards that become an exfiltration risk once combined, the lethal trifecta.
- **Autonomy level**: whether an interaction is a synchronous tool call, an asynchronous wakeup, or a step that needs a human in the loop.

{% include excalidraw.html file="02-taxonomy" alt="Lifecycle (build, consume, test, ship, run) as the primary axis, sync versus async as the secondary axis inside build, and the seven forces as the third dimension every entry declares" caption="Figure 2.2: Lifecycle, then sync or async, then the forces on every entry" %}

## The organizing taxonomy

Entries group first by where an API sits in its own lifecycle: building the
surface, consuming it from an agent harness, testing its contract and its
behavior, shipping it, and running it under observation and guardrails in
production. Lifecycle is the primary axis because the concerns at each stage
are different problems, not depths of the same one. Inside the
building stage, a second axis separates synchronous patterns, a REST or
GraphQL endpoint reshaped into an MCP tool an agent calls directly, from
asynchronous ones, a Kafka event that wakes an agent up on its own schedule
instead of waiting to be polled. The forces above sit as a third dimension
across both axes: every entry, wherever it falls on the first two, states
which forces it resolves and which it knowingly trades away, so two entries
from different stages or different axes still compare on the same terms.

## The landscape vocabulary

A short, shared vocabulary recurs across the catalog. Each term gets one
gloss here; the mechanics of using any one of them belong to the pattern page
that actually builds it.

| Term | What it names |
|---|---|
| MCP tools, resources, prompts | The Model Context Protocol's three primitives: a callable action, a readable piece of context, and a reusable prompt template. MCP itself is a vendor-neutral specification under the Linux Foundation, not any one vendor's protocol. |
| Function or tool calling | A model's native ability to emit a structured call against a registered function signature, the mechanism MCP tools and plain function-calling APIs both rely on underneath. |
| OpenAPI as tools | Converting an existing OpenAPI document into a tool list automatically. A naive, mechanical conversion reproduces every weakness a human-facing REST surface already has, granular endpoints, opaque errors, pagination, which is why it is treated as a caution here rather than a shortcut. |
| GraphQL introspection | A GraphQL schema's own machine-readable description of its types and fields, usable the same way an OpenAPI document is, with field selection doing some of the token-economy work a REST response shape has to do by hand. |
| `llms.txt` | A plain-text convention for pointing a model at the pages or resources most relevant to using a site or an API, read by a model instead of a browser. |
| `AGENTS.md` | A plain-text convention for telling a coding agent how to build, test and run a specific repository, read by an agent instead of a human contributor. |

One caution stands apart from the table: reaching for MCP by default, for
every integration regardless of shape, is itself a named anti-pattern below. A
well-documented command-line tool with structured JSON output, or a plain
function call, often serves a task MCP would otherwise be used for out of
habit.

## Foundational principles

A handful of principles get named once and invoked by name throughout: the
**contract is the instruction set**, so ambiguity in a tool definition becomes
a wrong call, not a slower human; design for the **consumer's affordances**
rather than wrapping an existing human-facing API unchanged; **resolve
ambiguity in the backend** instead of asking a caller to supply something it
cannot reliably know; **the model reasons, the application controls**,
meaning a model's decision to act and the system's decision to let that
action happen are never the same step; **budget the context**, treating every
tool definition and every response field as a cost paid on every call; prefer
**few high-impact tools over many granular ones**; and prefer **instructive
errors over opaque codes**, because an agent's only recovery signal is the
text an error actually contains.

## Anti-patterns

Named so each pattern page can point at what it argues against without
restating the argument: naive API-to-MCP wrapping, converting an existing
surface into tools mechanically instead of redesigning it for a caller with a
bounded window; tool inflation, registering dozens of granular tools until
selection accuracy collapses; chatty CRUD for agents, requiring several
sequenced calls for one business operation; opaque errors, a bare code with
no remedy text; MCP by default, reaching for a tool-calling protocol where a
well-documented CLI or a plain function call would do; passing raw
identifiers and UUIDs through the context window where a human-readable value
would cost fewer tokens and read more reliably; prompt as business logic,
trusting a model's own reasoning to enforce a rule the application should
enforce instead; and lethal-trifecta wiring, a single tool surface that can
read private data, ingest untrusted content and send data out, all at once.

A focused, multi-domain data mesh built from this catalog's own shipping
domain closes the lifecycle later: the same patterns from building, shipping
and running an agent-facing API, composed across more than one domain's
services into agent-facing data products, governed the way Dehghani's four
data mesh principles describe.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- ThoughtWorks Technology Radar, MCP-by-default and naive API-to-MCP conversion cautions: [thoughtworks.com/radar](https://www.thoughtworks.com/radar)
- Chip Huyen, *AI Engineering*, O'Reilly, 2024
- `llms.txt`: [llmstxt.org](https://llmstxt.org/)
- `AGENTS.md`: [agents.md](https://agents.md/)
- OpenAPI Specification
- GraphQL Specification
- Zhamak Dehghani, *Data Mesh: Delivering Data-Driven Value at Scale*, O'Reilly, 2022
