---
layout: plan
title: Versions
render_with_liquid: false
---

# Version pins (surveyed 2026-11-18, r02 re-survey)

Rule: latest stable upstream, exact pins, no prerelease, no floating tag. A
component is pinned once, at introduction; an existing pin moves only for a
valid reason (a security fix, a real incompatibility, or a feature a chapter
needs), recorded next to the pin. Versions appear in chapter 00 and the
appendices only (see CLAUDE.md).

## Pins

| Component | Pin (exact) | Surveyed | Notes/holds |
|---|---|---|---|
| Temurin JDK | 25.0.5-tem | 2026-11-18 | Re-confirmed at r02 against `api.adoptium.net` (25.0.5+8-LTS is latest 25.x): no move. JDK 25 is the minimum. |
| ubi10/openjdk-25 | registry.access.redhat.com/ubi10/openjdk-25:1.24-20 | 2026-11-18 | Re-verified at r02: tag resolves (`docker manifest inspect`, amd64 `sha256:21acbfd05f7d7c4e8f2eb4ce1efcb163dcd489362e37cbd52641acd503e09d4d`). Build image; no move. |
| ubi10/openjdk-25-runtime | registry.access.redhat.com/ubi10/openjdk-25-runtime:1.24-20 | 2026-11-18 | Re-verified at r02: tag resolves (`docker manifest inspect`, amd64 `sha256:36e3d61c301d99d893feb43bbc6569ba00e282da94ddcaf98ff20713ce27c2ac`). Runtime image, same release as the build image. ubi9/openjdk-25 is the fallback only on a real incompatibility. |
| Quarkus platform (quarkus-bom, quarkus-camel-bom) | io.quarkus.platform:quarkus-bom:3.40.1.2 | 2026-11-18 | Re-confirmed at r02 against the Maven Central `quarkus-bom` metadata: 3.40.1.2 is still the newest 3.40.x patch. 3.40 LTS stream; no move. |
| Apache Camel (core) | org.apache.camel:camel-bom:4.22.2 | 2026-11-18 | LTS line (end of life Aug 2027); imported ahead of the platform BOM in every Camel example. |
| quarkus-mcp-server | io.quarkiverse.mcp:quarkus-mcp-server-bom:2.0.4 | 2026-11-18 | HTTP and stdio transports. 2.1.0 is a prerelease, skipped. |
| MCP protocol revision | 2025-11-25 | 2026-11-18 | Verify this is what quarkus-mcp-server-bom 2.0.4 advertises when the first MCP example lands. |
| quarkus-langchain4j | io.quarkus.platform:quarkus-langchain4j-bom:3.40.1.2 manages quarkus-langchain4j 1.13.4 / langchain4j 1.19.3 | 2026-11-18 | Confirmed by fetching `quarkus-langchain4j-bom-3.40.1.2.pom` from Maven Central directly: it manages `io.quarkiverse.langchain4j:quarkus-langchain4j-core:1.13.4` and `dev.langchain4j:langchain4j-bom:1.19.3`. HOLD on upstream 1.15.x: the platform's tested set stays, matching the event-driven-patterns-with-quarkus-and-camel sibling's decision. |
| quarkus-langchain4j-ollama | io.quarkiverse.langchain4j:quarkus-langchain4j-ollama:1.13.4 | 2026-11-18 | New r02 consuming-side dependency. Confirmed present in the `quarkus-langchain4j-bom:3.40.1.2` managed-dependency list at the same version as `quarkus-langchain4j-core` (Maven Central pom fetch). |
| Ollama | docker.io/ollama/ollama:0.40.16@sha256:a372a1c0d1e15b68e9521189785c3ca17795c26c2356f8b1b10122cfb59ed0d7 | 2026-11-18 | Re-surveyed at r02: pinned by the manifest-list digest (`docker manifest inspect docker.io/ollama/ollama:0.40.16`), so the digest resolves to the correct per-platform image on any host. Differs from the sibling's amd64-only manifest digest (`sha256:910d0d8...`) by design: this pin is the index digest, not an arch-specific one. |
| Model | qwen3:4b-instruct-2507-q4_K_M, manifest digest `0edcdef34593` | 2026-11-18 | Re-surveyed at r02: pulled live into a throwaway container against the pin above and confirmed with `ollama list`; digest matches the sibling's record. Apache-2.0, tool-calling capable. |
| Apache Kafka | 4.3.2 | 2026-11-18 | Held to what Strimzi 1.3.1 supports (default: true at 4.3.2). |
| docker.io/apache/kafka | docker.io/apache/kafka:4.3.2 | 2026-11-18 | Re-verified at r02: tag resolves (`docker manifest inspect`). KRaft single node; also the Kafka Dev Services image. |
| Strimzi operator | quay.io/strimzi/operator:1.3.1 | 2026-11-18 | Used at the final-project iteration (Part 7). |
| CloudNativePG operator | v1.30.1 | 2026-11-18 | Used at the final-project iteration. |
| CNPG operand image | ghcr.io/cloudnative-pg/postgresql:18.6-202611090812-standard-trixie | 2026-11-18 | Re-verified at r02: tag resolves (`docker manifest inspect`, index digest `sha256:45317a79602e17ce64e747fd0e06e0d5e1d692a9008e8d4e2347eebdd830b8b9`). Used by the compose stack. |
| Apicurio Registry | quay.io/apicurio/apicurio-registry:3.3.4 | 2026-11-18 | Re-verified at r02: tag resolves (`docker manifest inspect`). Not the `-mutable` variant. |
| Keycloak | quay.io/keycloak/keycloak:26.8.2 | 2026-11-18 | Re-verified at r02: `26.8.2` and the rebuild-numbered `26.8.2-2` resolve to the identical digest (`sha256:4da21f9a53bcbac1026a0d654b740a81e98e5de4c45457076acc74b4d94b1551`), so the tag is not floating. Book-wide identity provider; `start-dev` mode for local compose only. |
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
