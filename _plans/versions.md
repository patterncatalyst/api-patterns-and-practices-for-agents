---
layout: plan
title: Versions
render_with_liquid: false
---

# Version pins (surveyed 2026-11-18)

Rule: latest stable upstream, exact pins, no prerelease, no floating tag. A
component is pinned once, at introduction; an existing pin moves only for a
valid reason (a security fix, a real incompatibility, or a feature a chapter
needs), recorded next to the pin. Versions appear in chapter 00 and the
appendices only (see CLAUDE.md).

## Pins

| Component | Pin (exact) | Surveyed | Notes/holds |
|---|---|---|---|
| Temurin JDK | 25.0.5-tem (re-confirm newest 25.x at r02) | 2026-11-18 | JDK 25 is the minimum. |
| ubi10/openjdk-25 | registry.access.redhat.com/ubi10/openjdk-25:1.24-20 | 2026-11-18 | Build image; re-verify digest at r02. |
| ubi10/openjdk-25-runtime | registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24-20 | 2026-11-18 | Runtime image, same release as the build image. ubi9/openjdk-25 is the fallback only on a real incompatibility. |
| Quarkus platform (quarkus-bom, quarkus-camel-bom) | io.quarkus.platform:quarkus-bom:3.40.1.2 | 2026-11-18 | 3.40 LTS stream; confirm newest 3.40.x at r02. |
| Apache Camel (core) | org.apache.camel:camel-bom:4.22.2 | 2026-11-18 | LTS line (end of life Aug 2027); imported ahead of the platform BOM in every Camel example. |
| quarkus-mcp-server | io.quarkiverse.mcp:quarkus-mcp-server-bom:2.0.4 | 2026-11-18 | HTTP and stdio transports. 2.1.0 is a prerelease, skipped. |
| MCP protocol revision | 2025-11-25 | 2026-11-18 | Verify this is what quarkus-mcp-server-bom 2.0.4 advertises when the first MCP example lands. |
| quarkus-langchain4j | io.quarkus.platform:quarkus-langchain4j-bom:3.40.1.2 manages quarkus-langchain4j 1.13.4 / langchain4j 1.19.3 | 2026-11-18 | HOLD on upstream 1.15.x: the platform's tested set stays, matching the event-driven-patterns-with-quarkus-and-camel sibling's decision. |
| Ollama | docker.io/ollama/ollama:0.40.16 | 2026-11-18 | |
| Model | qwen3:4b-instruct-2507-q4_K_M | 2026-11-18 | Apache-2.0, tool-calling capable. |
| Apache Kafka | 4.3.2 | 2026-11-18 | Held to what Strimzi 1.3.1 supports (default: true at 4.3.2). |
| docker.io/apache/kafka | docker.io/apache/kafka:4.3.2 | 2026-11-18 | KRaft single node; also the Kafka Dev Services image. |
| Strimzi operator | quay.io/strimzi/operator:1.3.1 | 2026-11-18 | Used at the final-project iteration (Part 7). |
| CloudNativePG operator | v1.30.1 | 2026-11-18 | Used at the final-project iteration. |
| CNPG operand image | ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-standard-trixie | 2026-11-18 | Used by the compose stack now (`examples/_infra/compose.yaml`); re-verify the digest at r02. |
| Apicurio Registry | quay.io/apicurio/apicurio-registry:3.3.4 | 2026-11-18 | Not the `-mutable` variant. |
| Keycloak | quay.io/keycloak/keycloak:26.8.2 | 2026-11-18 | Book-wide identity provider; `start-dev` mode for local compose only. |
| grafana/otel-lgtm | docker.io/grafana/otel-lgtm:0.41.0 | 2026-11-18 | |
| Ruby image / Jekyll | docker.io/library/ruby:4.0.9 / Jekyll 4.4.1 | 2026-11-18 | Site build container. |
| Newman | pinned at Part 5 (scripted non-interactive consumption) | | Not yet surveyed; r08-equivalent. |

## OTel GenAI semantic conventions

The OpenTelemetry Semantic Conventions for Generative AI systems are at
**Development** status as of this survey. Content capture (prompts and
completions in span attributes) is **off by default** (PII risk); the
observability chapter (Part 7) pins the opt-in explicitly and derives cost
downstream from token-count attributes rather than always-on content capture.

## Re-survey discipline

Every iteration that introduces a new dependency re-surveys it here at G0 and
records the source URL and the date. An iteration that only writes chapters
against already-pinned dependencies does not re-survey them; pins are frozen
until a valid reason to move one appears.
