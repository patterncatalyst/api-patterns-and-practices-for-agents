---
layout: plan
title: "Iteration plan"
render_with_liquid: false
---

# Iteration plan

Each iteration follows lgtm-relay: Opus plans, the owner reviews the plan,
Sonnet builds, Opus validates. One `release/rNN` branch and PR per iteration,
squash-merged on the owner's OK, tagged `rNN.x`, notes-only release.

## Part-to-chapter outline

`(NEW)` marks a chapter added or split during planning review.

### Part 0: Foundations

- 00 Prerequisites and the two stacks
- 01 Why agent-facing APIs are different
- 02 How to read this catalog (NEW)

### Part 1: Build/sync (REST+GraphQL as MCP tools)

- 03 Intent Endpoint (over CRUD)
- 04 Search over Pagination
- 05 Batch Operation and Count Flag
- 06 Idempotency and Safe Retries (NEW)
- 07 Strict Self-Describing Contract (OpenAPI 3.1)
- 08 Semantic Metadata and llms.txt + Dual-Use Portal
- 09 Token-Efficient Response / Agent BFF
- 10 Instructive Error (RFC 9457)
- 11 Propose/Preview/Commit (NEW)
- 12 MCP-First single CDI bean (REST+GraphQL+MCP @Tool)

### Part 2: Build/MCP packaging

- 13 Package an API as MCP Tools (quarkus-mcp-server)
- 14 Domain-Split Toolset and Progressive Discovery
- 15 Reshape not Wrap: heterogeneous protocols (the single gRPC-behind-facade pattern; payment is a natural gRPC service; depth is deferred to the API Patterns & Practices: gRPC sibling)
- 16 Securing and Gating MCP Tools (NEW)

### Part 3: Build/async (Kafka/Camel)

- 17 Event-Triggered Agent Wakeup
- 18 Multi-Agent Choreography
- 19 Human-in-the-Loop Gate (three-mechanism table)
- 20 Long-Running Agent Workflow (Camel Saga/LRA + Quarkus Flow)
- 21 Replayable Agent Memory / Event-Sourced Audit

### Part 4: Consume (Claude Code + langchain4j)

- 22 Consuming an MCP Server from Claude Code
- 23 Building a Claude Code Skill
- 24 langchain4j Agent as a Programmatic Consumer (direct quarkus-langchain4j path; avoid the Camel langchain4j-agent Ollama tool-calling bug)
- 25 Consuming the Async Side
- 26 The Prompt-plus-Contract Loop and Decision-Rule Review

### Part 5: Test and automate

- 27 Contract and Manifest Conformance
- 28 Agent-Behaviour Eval with Deterministic Replay
- 29 Scripted Non-Interactive Consumption (Newman; negative-witness bite)
- 30 Testing Async Flows and the HITL Gate

### Part 6: Ship

- 31 Publish the Tool Surface (MCP image + stdio jar, llms.txt, versioned releases)
- 32 CI for Agent-Facing APIs (contract-drift + Apicurio BACKWARD gate)

### Part 7: Run in production

- 33 Observability for Agent Traffic (outward audit; OTel GenAI conventions, token/cost)
- 34 Auditing AI-Using Code (NEW; inward audit of our own langchain4j code calling other APIs)
- 35 Identity and Authorization Guardrails (SPLIT a; Keycloak OIDC, PKCE, client_credentials, Token Exchange RFC 8693, step-up ACR; IdP-neutral in callouts)
- 36 Traffic and Resource Guardrails (SPLIT b; rate limits, topic ACLs, schema enforcement, quotas)
- 37 Running on Kubernetes with Strimzi
- 38 Final project: the focused multi-domain data mesh (3 to 4 agent-facing data products from the shipping domain, Dehghani's four principles, reuse datamesh-reference-arch-quarkus by reference; "final project", never "capstone")

### Part 8: Appendices

- A llms.txt in depth
- B OpenShift Local (CRC)
- C Streaming comparison (WebSockets/webhooks/gRPC) + gRPC-behind-facade note
- D Pattern map and glossary + cross-stack transfer table (Python/FastAPI, Strawberry GraphQL)
- E Building the consuming skill in Claude Code, end to end
- F Deterministic decisioning behind agent tools (Drools/Kogito, DMN): a rules/DMN engine
  behind a tool makes a business decision deterministic, the concrete answer to the
  Prompt-as-Business-Logic anti-pattern and the Model-Reasons-Application-Controls
  principle; distinct from Part 3's model-call determinism (the asynchronous patterns
  use a different mechanism to make a workflow step deterministic); one focused example,
  the refund-approval decision reimplemented as rules; built in a later iteration

## Release table

| Iter | Scope | Chapters | Status |
|---|---|---|---|
| r01 | Repo, Jekyll site, CLAUDE.md, `_plans`, tooling, CI, contracts and examples infra skeleton | none (skeleton + plan only) | done (this release, stop for review) |
| r02 | Part 0 + wired compose stack (+Keycloak) + `examples/domain-model` module + 00-stack-check | 00 to 02 | next |
| r03 | Build/sync, batch | 03 to 06 | |
| r04 | Build/sync, contracts and errors | 07 to 12 | |
| r05 | Build/MCP packaging | 13 to 16 | |
| r06 | Build/async | 17 to 21 | |
| r07 | Consume (+ skills/ consuming skill) | 22 to 26 | |
| r08 | Test and automate | 27 to 30 | |
| r09 | Ship | 31 to 32 | |
| r10 | Run: observability, auditing, guardrails | 33 to 36 | |
| r11 | Run: Kubernetes + final project | 37 to 38 | |
| r12 | Appendices A, C, D, E, F | A, C, D, E, F | |
| r13 | Appendix B (OpenShift Local) | B | |
| r14 | Retro: idiomatic review, voice review, QA sweep, host scrub, full fresh re-run | all | |
| r15 | The deck (decks-last) | none | |
