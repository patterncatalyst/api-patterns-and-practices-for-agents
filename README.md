# API Patterns & Practices: APIs for Agents & LLMs

A chaptered pattern catalog, in the style of Enterprise Integration Patterns,
for building APIs that AI agents and large language models consume rather
than humans. The vehicle is Quarkus, Apache Camel, and Kafka: REST and
GraphQL endpoints reshaped as MCP tools, event-driven flows an agent can wake
up on, and the guardrails (identity, rate limits, schema enforcement,
observability) a production agent-facing surface needs. Every pattern names
the stack-neutral contract first, so the same idea ports to Python/FastAPI
and Strawberry GraphQL, and most chapters invite the reader to build the
pattern twice: once in this book's stack, once in their own.

Site: https://patterncatalyst.github.io/api-patterns-and-practices-for-agents/

## Status

This is the **r01 skeleton**: the Jekyll site, tooling, CI, plan artifacts,
and infrastructure scaffolding. No chapters and no runnable examples ship
yet; those land starting r02. See `_plans/iteration-plan.md` for the full
Part-to-chapter outline and release schedule.

## What it covers

| Part | Topic |
|------|-------|
| 0 | Foundations: the two stacks, why agent-facing APIs differ, how to read the catalog |
| 1 | Build/sync: REST and GraphQL patterns an agent can call as MCP tools |
| 2 | Build/MCP: packaging an API's tool surface, progressive discovery, securing tools |
| 3 | Build/async: Kafka and Camel patterns for waking up and coordinating agents |
| 4 | Consume: calling an MCP server and the async side from Claude Code and langchain4j |
| 5 | Test: contract conformance, agent-behaviour evals, scripted and async testing |
| 6 | Ship: publishing the tool surface, CI for agent-facing APIs |
| 7 | Run: observability, auditing AI-using code, identity and traffic guardrails, Kubernetes, the final project |
| Appendices | llms.txt in depth, OpenShift Local, streaming comparison, pattern map and glossary, building a consuming skill |

## Stack

Quarkus, Apache Camel, and Kafka, with `quarkus-mcp-server` for MCP tool
packaging, Keycloak as the book-wide identity provider, Apicurio for schema
governance, langchain4j (via `quarkus-langchain4j`) and Ollama for the
consuming side, and the Grafana LGTM stack for observability. No Python or
FastAPI tab in this book: the stack-neutral contract in every chapter's
"Explore with your agent" callout is how the patterns transfer to another
stack, including Python/FastAPI and Strawberry GraphQL (see Appendix D).

## Run targets

- **Docker Compose** for local development: Kafka (KRaft), PostgreSQL,
  Apicurio Registry, Keycloak, Ollama, and Grafana LGTM. Declared under
  `examples/_infra/` from r01; wired into a runnable stack starting r02.
- **Kubernetes with Strimzi** for the final project (Part 7).
- **OpenShift Local** appendix, mirroring the compose and Kubernetes paths on
  an on-premises Kubernetes distribution.

## Repository layout

```
_docs/                 chapters and appendices (Jekyll docs collection; empty until r02)
_parts/                part cards for the homepage and chapter index
_example_pages/        example pages on the site
_plans/                iteration plan, domain mirror, version pins, claims (not published)
_layouts/ _includes/   site templates
assets/                site CSS and diagrams
contracts/             OpenAPI, GraphQL SDL, MCP manifests, and Avro/JSON schemas as they land
examples/              one runnable project per pattern chapter (from r02)
examples/_infra/       shared compose stack
examples/domain-model/ shared order-to-delivery records (module lands r02)
scripts/               validation, voice/idiomatic scans, host-id scrub, diagram, and CI helper scripts
bibliography.md        references
```

## Build the site

The host needs no Ruby. The site builds in a container:

```bash
scripts/site-build.sh            # builds into _site/
scripts/site-build.sh --serve    # serves on http://127.0.0.1:4000/
```

## Licenses

Code is licensed under Apache-2.0 ([LICENSE](LICENSE)). Prose and figures are
licensed under CC BY 4.0 ([LICENSE-docs.md](LICENSE-docs.md)).
