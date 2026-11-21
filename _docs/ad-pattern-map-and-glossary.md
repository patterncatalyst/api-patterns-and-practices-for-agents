---
title: "Pattern map, glossary, and cross-stack transfer table"
order: 42
marker: "Appendix D"
part: "Appendices"
description: "Every pattern chapter mapped to its part, its problem and its neighbors, the catalog's recurring terms defined once, and every pattern's Python/FastAPI and Strawberry GraphQL equivalent gathered into one cross-stack reference."
duration: "35 minutes"
---

Thirty-four pattern chapters span chapters 3 through 36, each stating its own
problem and its own stack-neutral contract in isolation. A reader returning
later, or porting the catalog onto Python/FastAPI, needs what one chapter
alone withholds: where a pattern sits next to its neighbors, what the
catalog's recurring terms mean, and where every cross-stack callout landed.
This appendix holds that lookup in one place.

## Pattern map

Part, pattern name (or role, for the five that name no pattern), the problem
it answers, and what it composes with or substitutes for, in the spirit of
Hohpe and Woolf's own pattern relationships. "Related" names a neighbor,
never a dependency to read first.

| Ch. | Part | Pattern | Problem it answers | Related |
|---|---|---|---|---|
| [00]({{ '/docs/00-prerequisites-and-the-two-stacks/' | relative_url }}) | Foundations | Not a pattern: toolchain and two stacks | One pinned toolchain, two stacks that never depend on each other. | Feeds every later chapter. |
| [01]({{ '/docs/01-why-agent-facing-apis-are-different/' | relative_url }}) | Foundations | Not a pattern: states and measures the five pressures | An agent-facing contract is a measurably different job than a human-facing one. | Sets up [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }}) and every pattern's forces. |
| [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }}) | Foundations | Not a pattern: pattern form, seven forces, taxonomy | Unrelated essays cannot compare without one shared form and force set. | Defines the form every pattern (03-36) uses. |
| [03]({{ '/docs/03-intent-endpoint-over-crud/' | relative_url }}) | Build: sync | Intent Endpoint (over CRUD) | A chatty CRUD sequence leaves an observable half-done state. | [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) (shares the write); [11]({{ '/docs/11-propose-preview-commit/' | relative_url }}) (preview on the same endpoint). |
| [04]({{ '/docs/04-search-over-pagination/' | relative_url }}) | Build: sync | Search over Pagination | Pagination answers what is on this page, not which rows match. | [05]({{ '/docs/05-batch-operation-and-count-flag/' | relative_url }}), [09]({{ '/docs/09-token-efficient-response-bff/' | relative_url }}) (same narrow-question family). |
| [05]({{ '/docs/05-batch-operation-and-count-flag/' | relative_url }}) | Build: sync | Batch Operation and Count Flag | Resolving ids or counting costs a full record's price per answer. | [04]({{ '/docs/04-search-over-pagination/' | relative_url }}), [09]({{ '/docs/09-token-efficient-response-bff/' | relative_url }}). |
| [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) | Build: sync | Idempotency and Safe Retries | A retried write cannot tell a failed attempt from a lost response. | [03]({{ '/docs/03-intent-endpoint-over-crud/' | relative_url }}) (carries the key); [20]({{ '/docs/20-long-running-agent-workflow/' | relative_url }}), [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}) (same guard elsewhere). |
| [07]({{ '/docs/07-strict-self-describing-contract/' | relative_url }}) | Build: sync | Strict Self-Describing Contract | A hand-maintained contract drifts the moment nobody updates it. | [08]({{ '/docs/08-semantic-metadata-llms-txt/' | relative_url }}) (same operations); [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}), [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}) (check it live). |
| [08]({{ '/docs/08-semantic-metadata-llms-txt/' | relative_url }}) | Build: sync | Semantic Metadata and Dual-Use Portal | Two hand-written documents of one API agree only by luck. | [07]({{ '/docs/07-strict-self-describing-contract/' | relative_url }}); [31]({{ '/docs/31-publish-the-tool-surface/' | relative_url }}) (llms.txt kept in version lockstep). |
| [09]({{ '/docs/09-token-efficient-response-bff/' | relative_url }}) | Build: sync | Token-Efficient Response (Agent BFF) | A human-shaped payload charges tokens for fields a task never needed. | [04]({{ '/docs/04-search-over-pagination/' | relative_url }}), [05]({{ '/docs/05-batch-operation-and-count-flag/' | relative_url }}); [12]({{ '/docs/12-mcp-first-single-bean/' | relative_url }}) (same bean, projected). |
| [10]({{ '/docs/10-instructive-error/' | relative_url }}) | Build: sync | Instructive Error (RFC 9457) | A bare status code gives nothing to decide what to try next. | [15]({{ '/docs/15-reshape-not-wrap-heterogeneous-protocols/' | relative_url }}), [36]({{ '/docs/36-traffic-and-resource-guardrails/' | relative_url }}) (same instructive shape elsewhere). |
| [11]({{ '/docs/11-propose-preview-commit/' | relative_url }}) | Build: sync | Propose, Preview, Commit | An irreversible write has no way to be seen before it runs. | [03]({{ '/docs/03-intent-endpoint-over-crud/' | relative_url }}) (same endpoint); [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}), [26]({{ '/docs/26-decision-rule-review/' | relative_url }}) (what gets reviewed). |
| [12]({{ '/docs/12-mcp-first-single-bean/' | relative_url }}) | Build: MCP | MCP-First Single CDI Bean | Three per-transport implementations of one read can quietly stop agreeing. | [09]({{ '/docs/09-token-efficient-response-bff/' | relative_url }}); [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}) (the bean gets packaged). |
| [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}) | Build: MCP | Package an API as MCP Tools | Wrapping each endpoint as its own tool carries the API's chattiness forward. | [12]({{ '/docs/12-mcp-first-single-bean/' | relative_url }}); [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }}) (splits it once it grows); [15]({{ '/docs/15-reshape-not-wrap-heterogeneous-protocols/' | relative_url }}). |
| [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }}) | Build: MCP | Domain-Split Toolset and Progressive Discovery | A flat, growing tool list degrades selection accuracy and risks collisions. | [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}); [16]({{ '/docs/16-securing-and-gating-mcp-tools/' | relative_url }}) (each server still gates). |
| [15]({{ '/docs/15-reshape-not-wrap-heterogeneous-protocols/' | relative_url }}) | Build: MCP | Reshape not Wrap: Heterogeneous Protocols | An unreshaped facade leaks a foreign protocol's wire shapes through. | [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}); [10]({{ '/docs/10-instructive-error/' | relative_url }}) (the reshaped failure path). |
| [16]({{ '/docs/16-securing-and-gating-mcp-tools/' | relative_url }}) | Build: MCP | Securing and Gating MCP Tools | An open surface, or one gated only in prose, gives a model unguarded reach. | [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }}); [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}) (confirmation on a mutation); [35]({{ '/docs/35-identity-and-authorization-guardrails/' | relative_url }}) (role/scope to assurance). |
| [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}) | Build: async | Event-Triggered Agent Wakeup | No poll interval is both prompt and cheap. | [18]({{ '/docs/18-multi-agent-choreography/' | relative_url }}), [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}) (same wakeup, two sides); [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) (the record it leaves). |
| [18]({{ '/docs/18-multi-agent-choreography/' | relative_url }}) | Build: async | Multi-Agent Choreography | A hidden orchestrator couples three agents to one place that must change. | [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}); [20]({{ '/docs/20-long-running-agent-workflow/' | relative_url }}) (alternative shape); [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) (the trace of the chain). |
| [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}) | Build: async | Human-in-the-Loop Gate | Deciding and acting in one call leaves no seam for a human. | [11]({{ '/docs/11-propose-preview-commit/' | relative_url }}); [26]({{ '/docs/26-decision-rule-review/' | relative_url }}) (alternative or complementary gate); [30]({{ '/docs/30-testing-async-flows-and-hitl-gate/' | relative_url }}) (how it is tested). |
| [20]({{ '/docs/20-long-running-agent-workflow/' | relative_url }}) | Build: async | Long-Running Agent Workflow | No transaction spans a multi-service process; a partial failure has no way back. | [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) (idempotent compensations); [18]({{ '/docs/18-multi-agent-choreography/' | relative_url }}) (alternative shape). |
| [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) | Build: async | Replayable Agent Memory / Event-Sourced Audit | A projection that is also the record of truth cannot check or rebuild itself. | [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}), [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}); [28]({{ '/docs/28-agent-behaviour-eval/' | relative_url }}) (same captured signals); [34]({{ '/docs/34-auditing-ai-using-code/' | relative_url }}) (outbound counterpart). |
| [22]({{ '/docs/22-consuming-an-mcp-server-from-claude-code/' | relative_url }}) | Consume | Consuming an MCP Server from Claude Code | One interactive session proves nothing about the next change. | [23]({{ '/docs/23-building-a-claude-code-skill/' | relative_url }}) (packaged); [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}) (same boundary, checked deterministically). |
| [23]({{ '/docs/23-building-a-claude-code-skill/' | relative_url }}) | Consume | Building a Claude Code Skill | Stale skill prose can let a script fabricate an answer. | [22]({{ '/docs/22-consuming-an-mcp-server-from-claude-code/' | relative_url }}); [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}) (the skill's own coverage check). |
| [24]({{ '/docs/24-langchain4j-programmatic-consumer/' | relative_url }}) | Consume | LangChain4j Agent as a Programmatic Consumer | A model's retelling of a result is nondeterministic; the result is not. | [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) (captured over retold); [28]({{ '/docs/28-agent-behaviour-eval/' | relative_url }}) (same discipline). |
| [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}) | Consume | Consuming the Async Side | Polling is late; an unguarded consumer can act on a redelivery twice. | [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}); [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) (the conflict guard); [30]({{ '/docs/30-testing-async-flows-and-hitl-gate/' | relative_url }}). |
| [26]({{ '/docs/26-decision-rule-review/' | relative_url }}) | Consume | Decision-Rule Review | A model that proposes and judges its own proposal inherits its own nondeterminism. | [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}) (alternative or complementary gate); [11]({{ '/docs/11-propose-preview-commit/' | relative_url }}) (what gets reviewed). |
| [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}) | Test | Contract and Manifest Conformance | A readiness probe proves a process is up, never that it still matches its contract. | [07]({{ '/docs/07-strict-self-describing-contract/' | relative_url }}); [22]({{ '/docs/22-consuming-an-mcp-server-from-claude-code/' | relative_url }}); [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}) (wired into a merge gate). |
| [28]({{ '/docs/28-agent-behaviour-eval/' | relative_url }}) | Test | Agent-Behaviour Eval with Deterministic Replay | Judging an agent by its own transcript is judging it by its own testimony. | [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}), [24]({{ '/docs/24-langchain4j-programmatic-consumer/' | relative_url }}); [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}). |
| [29]({{ '/docs/29-scripted-non-interactive-consumption/' | relative_url }}) | Test | Scripted Non-Interactive Consumption | An exit code collapses every assertion into one bit that can hide a drift. | [07]({{ '/docs/07-strict-self-describing-contract/' | relative_url }}) (the contract it drives); [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}). |
| [30]({{ '/docs/30-testing-async-flows-and-hitl-gate/' | relative_url }}) | Test | Testing Async Flows and the Human-in-the-Loop Gate | An async, human-gated action has no return value worth asserting on. | [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}), [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}); [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) (exactly-once under redelivery). |
| [31]({{ '/docs/31-publish-the-tool-surface/' | relative_url }}) | Ship | Publish the Tool Surface | Independent artifacts agree on a version only by coincidence. | [08]({{ '/docs/08-semantic-metadata-llms-txt/' | relative_url }}) (llms.txt kept synced); [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}). |
| [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}) | Ship | CI for Agent-Facing APIs | Unit tests pass clean while the published contract or an event schema already broke. | [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}); [31]({{ '/docs/31-publish-the-tool-surface/' | relative_url }}). |
| [33]({{ '/docs/33-observability-for-agent-traffic/' | relative_url }}) | Run | Observability for Agent Traffic | A model call gives up its cost and failure cause unless something records it durably. | [34]({{ '/docs/34-auditing-ai-using-code/' | relative_url }}) (same durability, outbound side); [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}). |
| [34]({{ '/docs/34-auditing-ai-using-code/' | relative_url }}) | Run | Auditing AI-Using Code | An outbound call has nobody watching unless it is audited before it leaves. | [33]({{ '/docs/33-observability-for-agent-traffic/' | relative_url }}); [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) (the inbound counterpart). |
| [35]({{ '/docs/35-identity-and-authorization-guardrails/' | relative_url }}) | Run | Identity and Authorization Guardrails | A role and a scope say who may act, never how the credential was obtained. | [16]({{ '/docs/16-securing-and-gating-mcp-tools/' | relative_url }}) (extends role and scope with assurance). |
| [36]({{ '/docs/36-traffic-and-resource-guardrails/' | relative_url }}) | Run | Traffic and Resource Guardrails | No rate cap or shape check trusts the caller instead of the framework. | [10]({{ '/docs/10-instructive-error/' | relative_url }}) (the rejection shape); [35]({{ '/docs/35-identity-and-authorization-guardrails/' | relative_url }}) (both enforced, never by convention). |
| [37]({{ '/docs/37-running-on-kubernetes-with-strimzi/' | relative_url }}) | Run | Not a pattern: stands up the self-serve Kubernetes platform | A pattern proven on compose is not yet proven on a shared, multi-tenant platform. | Platform substrate for [38]({{ '/docs/38-final-project-data-mesh/' | relative_url }}); redeploys [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }})'s service unchanged. |
| [38]({{ '/docs/38-final-project-data-mesh/' | relative_url }}) | Run | Not a pattern: final project, three data products | A catalog of services is not a mesh until one scenario proves ownership and governance hold. | Composes [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}), [15]({{ '/docs/15-reshape-not-wrap-heterogeneous-protocols/' | relative_url }}), [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}) on [37]({{ '/docs/37-running-on-kubernetes-with-strimzi/' | relative_url }})'s platform; governs with 16/35 (scope), 21/34 (audit), [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}) (compatibility gate). |

{% include excalidraw.html file="ad-pattern-map-and-glossary" alt="The catalog's 34 named patterns placed on the lifecycle axis from Foundations through Build's sync, MCP and async sub-lanes, then Consume, Test, Ship and Run, with eight labelled edges, each naming the pair of chapters it connects: 07/27, 11/19, 19/26, 17/25, 21/28, 16/35 and 31-32/33-36" caption="Figure D.1: How the catalog's 34 patterns compose across the lifecycle" %}

## Glossary

Alphabetical, with a parenthesized number naming where each term gets built.

**Agent-Behaviour Eval**: a deterministic scorer checking a transcript's structured signals, tool calls, budgets, outcome, against committed assertions; scores a live run and a replayed fixture identically (ch. [28]({{ '/docs/28-agent-behaviour-eval/' | relative_url }})).

**AGENTS.md**: a plain-text convention telling a coding agent how to build, test and run a repository (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Choreography**: agents coordinating only through events they react to, with no agent calling another by name, unlike an orchestrator (ch. [18]({{ '/docs/18-multi-agent-choreography/' | relative_url }})).

**Context budget**: every tool definition and returned field sharing one bounded window with a task's instructions (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Data product**: a service owning its own database and output port inside a data mesh, so domain ownership is checkable (ch. [38]({{ '/docs/38-final-project-data-mesh/' | relative_url }})).

**Decision rule**: committed, ordered clauses evaluated by a plain function, never a model call, returning the first failed clause (ch. [26]({{ '/docs/26-decision-rule-review/' | relative_url }})).

**Dual-use portal**: a human portal and a machine-readable `llms.txt` rendered from one shared metadata source (ch. [08]({{ '/docs/08-semantic-metadata-llms-txt/' | relative_url }})).

**Error legibility**: an agent recovers only from what an error's text says, never from a stack trace a developer reads (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Idempotency key**: a required write-endpoint header, enforced server-side, that replays the first response on a repeat key and rejects a reused key carrying a different body (ch. [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }})).

**Instructive error**: an RFC 9457 problem response naming what failed and a remedy specific to it (ch. [10]({{ '/docs/10-instructive-error/' | relative_url }})).

**Intent endpoint**: one transaction-backed endpoint named for a whole business operation, replacing a chatty CRUD sequence (ch. [03]({{ '/docs/03-intent-endpoint-over-crud/' | relative_url }})).

**Lethal trifecta**: private data, untrusted content and an exfiltration path, combined in one surface (Simon Willison; ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }}), [16]({{ '/docs/16-securing-and-gating-mcp-tools/' | relative_url }})).

**MCP (Model Context Protocol) and tool surface**: a vendor-neutral, Linux Foundation specification for a model to discover and call tools, resources and prompts; the tool surface is whatever `tools/list` actually advertises (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }}), [22]({{ '/docs/22-consuming-an-mcp-server-from-claude-code/' | relative_url }})).

**Negative witness**: a `verify.sh` check that fails once a pattern's guard is removed, proving the guard does something (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Progressive discovery**: keeping a live `tools/list` small and reaching the rest of a domain through a shared catalog resource and a search tool (ch. [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }})).

**Propose/Preview/Commit**: a `dryRun` flag on the same intent endpoint returning a structured preview that writes nothing (ch. [11]({{ '/docs/11-propose-preview-commit/' | relative_url }})).

**Reasoning and execution boundary**: a model's decision to act and the application's decision to allow it are never the same step (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Replayable audit**: an append-only, event-sourced log folded into a projection that can be rebuilt from the log alone and compared to the live one (ch. [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }})).

**Saga / compensation**: a multi-step process whose steps each declare an idempotent undo, unwound in reverse order on a failure (ch. [20]({{ '/docs/20-long-running-agent-workflow/' | relative_url }})).

**Step-up authorization**: trading a low-assurance token for a stronger one through an OAuth 2.0 token exchange before a destructive tool accepts it (ch. [35]({{ '/docs/35-identity-and-authorization-guardrails/' | relative_url }})).

**Token economy**: every tool definition and returned field costs tokens in the agent's window, on every call (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

**Tool-selection accuracy**: a model picks a tool from its name and description alone; a crowded or colliding list degrades the pick (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }}), [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }})).

**Trust boundary**: private data, untrusted content and an exfiltration path, as three separate hazards (see lethal trifecta) (ch. [02]({{ '/docs/02-how-to-read-this-catalog/' | relative_url }})).

## Cross-stack transfer table

Every pattern's cross-stack callout closes with a `Build it elsewhere:` line
restating its contract for Python/FastAPI, and for Strawberry GraphQL
wherever the pattern has a GraphQL face. This table collects all 34 lines in
one reference.

| Ch. | Pattern | Python/FastAPI equivalent | GraphQL face |
|---|---|---|---|
| [03]({{ '/docs/03-intent-endpoint-over-crud/' | relative_url }}) | Intent Endpoint (over CRUD) | One transactional operation moving order and payment state together; rejects a second call on a non-cancellable order. | yes |
| [04]({{ '/docs/04-search-over-pagination/' | relative_url }}) | Search over Pagination | Typed filter endpoint or field returning only the matching orders, no offset sweep. | yes |
| [05]({{ '/docs/05-batch-operation-and-count-flag/' | relative_url }}) | Batch Operation and Count Flag | Batch endpoint/field resolving many ids with explicit misses; a count field selecting a number only. | yes |
| [06]({{ '/docs/06-idempotency-and-safe-retries/' | relative_url }}) | Idempotency and Safe Retries | Required idempotency key stored with the first response; replayed on repeat, rejected on a changed body. | yes |
| [07]({{ '/docs/07-strict-self-describing-contract/' | relative_url }}) | Strict Self-Describing Contract | Contract generated from the code's type annotations; every required field and enum declared. | yes |
| [08]({{ '/docs/08-semantic-metadata-llms-txt/' | relative_url }}) | Semantic Metadata and Dual-Use Portal | Plain-text summary and human page rendered from one shared metadata source. | yes |
| [09]({{ '/docs/09-token-efficient-response-bff/' | relative_url }}) | Token-Efficient Response (Agent BFF) | Purpose-built read selecting only needed fields at the data layer. | field selection narrows further |
| [10]({{ '/docs/10-instructive-error/' | relative_url }}) | Instructive Error (RFC 9457) | Each domain failure mapped to a problem response with a remedy specific to it. | yes |
| [11]({{ '/docs/11-propose-preview-commit/' | relative_url }}) | Propose, Preview, Commit | Dry-run flag on the same write returning a structured preview; a graceful decline distinct from an error. | yes |
| [12]({{ '/docs/12-mcp-first-single-bean/' | relative_url }}) | MCP-First Single CDI Bean | One shared function backing REST, a GraphQL resolver, and a tool-calling interface. | resolver is one of the three faces |
| [13]({{ '/docs/13-package-api-as-mcp-tools/' | relative_url }}) | Package an API as MCP Tools | FastAPI service behind an MCP server (official Python SDK) exposing one composed tool and two narrow tools. | not stated: no GraphQL face |
| [14]({{ '/docs/14-domain-split-toolset-progressive-discovery/' | relative_url }}) | Domain-Split Toolset, Progressive Discovery | Two FastAPI domain services, each its own small MCP server, plus a shared search tool and catalog. | not stated: no GraphQL face |
| [15]({{ '/docs/15-reshape-not-wrap-heterogeneous-protocols/' | relative_url }}) | Reshape not Wrap: Heterogeneous Protocols | FastAPI-fronted MCP server calling a separate gRPC service via a Python gRPC client; reshapes decimal-to-cents both ways. | not stated: no GraphQL face |
| [16]({{ '/docs/16-securing-and-gating-mcp-tools/' | relative_url }}) | Securing and Gating MCP Tools | FastAPI behind an MCP server checking role for a read, scope plus confirmation for a write. | not stated: no GraphQL face |
| [17]({{ '/docs/17-event-triggered-agent-wakeup/' | relative_url }}) | Event-Triggered Agent Wakeup | aiokafka consumer calling an agent's decision logic directly on an event; writes a durable outcome. | not stated: Kafka pattern |
| [18]({{ '/docs/18-multi-agent-choreography/' | relative_url }}) | Multi-Agent Choreography | Three independent aiokafka consumers, each reacting to one topic and publishing to the next. | not stated: Kafka pattern |
| [19]({{ '/docs/19-human-in-the-loop-gate/' | relative_url }}) | Human-in-the-Loop Gate | Pending-decision table and topic; a decision consumer claims each correlation id once under a row lock. | not stated: Kafka pattern |
| [20]({{ '/docs/20-long-running-agent-workflow/' | relative_url }}) | Long-Running Agent Workflow | Explicit saga runner enlisting a compensation per step, called in reverse order on failure. | not stated: no GraphQL face |
| [21]({{ '/docs/21-replayable-agent-memory-event-sourced-audit/' | relative_url }}) | Replayable Agent Memory / Event-Sourced Audit | Append-only aiokafka log folded by an idempotent upsert; a replay endpoint rebuilds and compares. | not stated: Kafka pattern |
| [22]({{ '/docs/22-consuming-an-mcp-server-from-claude-code/' | relative_url }}) | Consuming an MCP Server from Claude Code | Scripted client (official Python MCP SDK) running initialize, tools/list, tools/call; rejects an unadvertised tool. | yes |
| [23]({{ '/docs/23-building-a-claude-code-skill/' | relative_url }}) | Building a Claude Code Skill | Packaged bundle (instructions plus script) that checks a live capability list before calling. | yes |
| [24]({{ '/docs/24-langchain4j-programmatic-consumer/' | relative_url }}) | LangChain4j Agent as a Programmatic Consumer | Client capturing the raw response at the boundary; durable record from that, never the model's prose. | yes |
| [25]({{ '/docs/25-consuming-the-async-side/' | relative_url }}) | Consuming the Async Side | aiokafka consumer composing a reply and writing under a key derived from the event's own identity. | not stated: Kafka pattern |
| [26]({{ '/docs/26-decision-rule-review/' | relative_url }}) | Decision-Rule Review | Committed JSON rule of ordered clauses evaluated by a plain function, not a model. | yes |
| [27]({{ '/docs/27-contract-and-manifest-conformance/' | relative_url }}) | Contract and Manifest Conformance | Pinned conformance tool against a live service by network name; diffs served vs. committed; proven against a drifted copy. | yes |
| [28]({{ '/docs/28-agent-behaviour-eval/' | relative_url }}) | Agent-Behaviour Eval with Deterministic Replay | Deterministic scorer loading a committed spec, checking ordered assertions against structured transcript fields. | yes |
| [29]({{ '/docs/29-scripted-non-interactive-consumption/' | relative_url }}) | Scripted Non-Interactive Consumption | Committed collection or script driving create, read, unknown and invalid paths; judged by a structured report. | yes |
| [30]({{ '/docs/30-testing-async-flows-and-hitl-gate/' | relative_url }}) | Testing Async Flows and the Human-in-the-Loop Gate | Trigger, then poll a durable store and the decision topic; the gate holds until a decision, executes once. | yes |
| [31]({{ '/docs/31-publish-the-tool-surface/' | relative_url }}) | Publish the Tool Surface | One package version reported identically by the handshake, a one-shot's startup output, and a discovery document. | yes |
| [32]({{ '/docs/32-ci-for-agent-facing-apis/' | relative_url }}) | CI for Agent-Facing APIs | Pinned conformance tool plus a schema registry's own live compatibility rule, confirmed rather than assumed. | yes |
| [33]({{ '/docs/33-observability-for-agent-traffic/' | relative_url }}) | Observability for Agent Traffic | Read the client library's own token-usage signal; write a durable per-call cost row at a fixed, documented rate. | yes |
| [34]({{ '/docs/34-auditing-ai-using-code/' | relative_url }}) | Auditing AI-Using Code | One function every outbound call passes through, appending an audit record before the call goes out. | not stated: no GraphQL face |
| [35]({{ '/docs/35-identity-and-authorization-guardrails/' | relative_url }}) | Identity and Authorization Guardrails | FastAPI behind an MCP server checking role and scope plus an `acr` claim obtained only by token exchange. | not stated: no GraphQL face |
| [36]({{ '/docs/36-traffic-and-resource-guardrails/' | relative_url }}) | Traffic and Resource Guardrails | Rate limiter plus JSON Schema validation, each rejecting with its own error shape and durable counter. | yes |

## References

- Gregor Hohpe, Bobby Woolf, *Enterprise Integration Patterns*, Addison-Wesley, 2003
- Zhamak Dehghani, *Data Mesh: Delivering Data-Driven Value at Scale*, O'Reilly, 2022
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- `llms.txt`: [llmstxt.org](https://llmstxt.org/)
- RFC 9457: Problem Details for HTTP APIs: [rfc-editor.org/rfc/rfc9457](https://www.rfc-editor.org/rfc/rfc9457.html)
