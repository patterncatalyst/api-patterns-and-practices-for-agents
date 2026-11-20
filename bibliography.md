---
title: "Bibliography"
layout: plan
permalink: /bibliography/
description: "The books, specifications and documentation this site's coverage and rigor draw on: nothing here is reproduced from them."
---

This site teaches API design for AI agents and LLMs on Quarkus, Apache Camel
and Kafka, packaged as MCP tools and consumed by Claude Code and langchain4j.
The books and specifications below shaped what the chapters cover and how
rigorously they treat each pattern; the project and runtime documentation is
the authority for every API the examples use. These are reference works, not
sources: no text or code from any of them is reproduced here.

Works only: books, papers and specifications. Documentation pages are cited under each page's References.

## Books

<table class="bib bib--books">
  <colgroup><col style="width:62%"><col style="width:24%"><col style="width:14%"></colgroup>
  <thead><tr><th>Title</th><th>Author(s)</th><th>Year</th></tr></thead>
  <tbody>
    <tr><td><a href="https://www.oreilly.com/library/view/api-design-patterns/9781617295850/" rel="noopener"><em>API Design Patterns</em></a><br><small>Manning, ISBN 978-1-61729-585-0. Resource-oriented design, pagination, long-running operations, batch operations, and the error-model patterns this book reshapes for agent consumption</small></td><td>JJ Geewax</td><td>2021</td></tr>
    <tr><td><a href="https://www.enterpriseintegrationpatterns.com/" rel="noopener"><em>Enterprise Integration Patterns</em></a><br><small>Addison-Wesley, ISBN 978-0-321-20068-6. Content Enricher, Claim Check, Dead Letter Channel, Correlation Identifier, Process Manager: the pattern-catalog form and spine this book follows</small></td><td>Gregor Hohpe, Bobby Woolf</td><td>2003</td></tr>
    <tr><td><a href="https://www.oreilly.com/library/view/restful-web-apis/9781449359713/" rel="noopener"><em>RESTful Web APIs</em></a><br><small>O'Reilly, ISBN 978-1-4493-5971-3. Hypermedia, resource design, and the semantic-description patterns behind a self-describing contract</small></td><td>Leonard Richardson, Mike Amundsen, Sam Ruby</td><td>2013</td></tr>
    <tr><td><a href="https://www.oreilly.com/library/view/the-design-of/9781492042548/" rel="noopener"><em>The Design of Web APIs, Second Edition</em></a><br><small>Manning, ISBN 978-1-61729-949-0 (English translation). API-first design process, contract design, and usability for API consumers, human or automated</small></td><td>Arnaud Lauret</td><td>2024</td></tr>
    <tr><td><a href="https://www.oreilly.com/library/view/data-mesh/9781492092384/" rel="noopener"><em>Data Mesh: Delivering Data-Driven Value at Scale</em></a><br><small>O'Reilly, ISBN 978-1-4920-9238-4. The four data mesh principles (domain ownership, data as a product, self-serve platform, federated computational governance) behind the final project's data products</small></td><td>Zhamak Dehghani</td><td>2022</td></tr>
    <tr><td><a href="https://www.oreilly.com/library/view/ai-engineering/9781098166304/" rel="noopener"><em>AI Engineering: Building Applications with Foundation Models</em></a><br><small>O'Reilly, ISBN 978-1-098-16630-4. The practitioner framing for tool use, context engineering and agent evaluation this catalog's thesis and taxonomy draw on</small></td><td>Chip Huyen</td><td>2024</td></tr>
  </tbody>
</table>

## Specifications and standards

<table class="bib">
  <colgroup><col style="width:36%"><col style="width:24%"><col style="width:40%"></colgroup>
  <thead><tr><th>Title</th><th>Author</th><th>Used for</th></tr></thead>
  <tbody>
    <tr><td><a href="https://modelcontextprotocol.io/specification/2025-11-25" rel="noopener">Model Context Protocol specification</a></td><td>Anthropic, MCP working group</td><td>Tools, resources, prompts, progressive discovery, and the transport (stdio, Streamable HTTP) every MCP-packaged chapter targets</td></tr>
    <tr><td><a href="https://spec.openapis.org/oas/v3.1.1" rel="noopener">OpenAPI Specification 3.1.1</a></td><td>OpenAPI Initiative</td><td>The strict, self-describing REST contract every build/sync chapter ships</td></tr>
    <tr><td><a href="https://spec.graphql.org/" rel="noopener">GraphQL Specification</a></td><td>GraphQL Foundation</td><td>Schema-first design and the token-efficient response shaping chapters compare against REST</td></tr>
    <tr><td><a href="https://json-schema.org/specification" rel="noopener">JSON Schema specification</a></td><td>JSON Schema organization</td><td>The event-schema shape registered under Apicurio's BACKWARD compatibility rule</td></tr>
    <tr><td><a href="https://www.rfc-editor.org/rfc/rfc9457.html" rel="noopener">RFC 9457: Problem Details for HTTP APIs</a></td><td>IETF</td><td>The instructive-error pattern's wire format</td></tr>
    <tr><td><a href="https://www.rfc-editor.org/rfc/rfc8693.html" rel="noopener">RFC 8693: OAuth 2.0 Token Exchange</a></td><td>IETF</td><td>Delegated identity when an agent calls a tool on a user's behalf</td></tr>
    <tr><td><a href="https://www.rfc-editor.org/rfc/rfc9470.html" rel="noopener">RFC 9470: OAuth 2.0 Step Up Authentication Challenge Protocol</a></td><td>IETF</td><td>The acr step-up concept behind the destructive-tool assurance gate</td></tr>
    <tr><td><a href="https://openid.net/specs/openid-connect-core-1_0.html" rel="noopener">OpenID Connect Core 1.0</a></td><td>OpenID Foundation</td><td>The acr (Authentication Context Class Reference) claim carrying a token's assurance level</td></tr>
    <tr><td><a href="https://llmstxt.org/" rel="noopener">llms.txt</a></td><td>Jeremy Howard and contributors</td><td>The semantic-metadata and dual-use-portal pattern, and Appendix A</td></tr>
    <tr><td><a href="https://agents.md/" rel="noopener">AGENTS.md</a></td><td>AGENTS.md contributors</td><td>The agent-facing repository convention named in the catalog's landscape vocabulary</td></tr>
    <tr><td><a href="https://docs.anthropic.com/en/docs/build-with-claude/tool-use/overview" rel="noopener">Tool use with Claude</a></td><td>Anthropic</td><td>Tool-call shape, parallel tool use, and the contract an agent expects from a tool definition</td></tr>
    <tr><td><a href="https://www.anthropic.com/engineering/writing-tools-for-agents" rel="noopener">Writing effective tools for AI agents</a></td><td>Anthropic</td><td>Few high-impact tools over many granular ones, and the token cost of a tool's own definition</td></tr>
    <tr><td><a href="https://www.anthropic.com/engineering/effective-context-engineering-for-ai-agents" rel="noopener">Effective context engineering for AI agents</a></td><td>Anthropic</td><td>The bounded-context-window argument behind intent-over-CRUD and search-over-pagination</td></tr>
    <tr><td><a href="https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills" rel="noopener">Equipping agents for the real world with Agent Skills</a></td><td>Anthropic</td><td>Part 4's consuming-skill chapters and Appendix E</td></tr>
    <tr><td><a href="https://simonwillison.net/2025/Jun/16/the-lethal-trifecta/" rel="noopener">The lethal trifecta for AI agents</a></td><td>Simon Willison</td><td>The trust-boundary force: private data, untrusted content and a path to exfiltrate it, combined</td></tr>
    <tr><td><a href="https://www.thoughtworks.com/radar" rel="noopener">Technology Radar</a></td><td>ThoughtWorks</td><td>The MCP-by-default and naive-API-to-MCP-conversion cautions</td></tr>
  </tbody>
</table>

## Primary documentation

<table class="bib">
  <colgroup><col style="width:45%"><col style="width:55%"></colgroup>
  <thead><tr><th>Source</th><th>Used for</th></tr></thead>
  <tbody>
    <tr><td><a href="https://quarkus.io/guides/" rel="noopener">Quarkus</a></td><td>The Quarkus runtime, REST, GraphQL, Dev Services and Kafka messaging</td></tr>
    <tr><td><a href="https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html" rel="noopener">Quarkus MCP Server</a></td><td>Packaging a Quarkus API as MCP tools, HTTP and stdio transports</td></tr>
    <tr><td><a href="https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html" rel="noopener">Quarkus LangChain4j</a></td><td>AI services, chat models, and the Ollama integration on the consuming side</td></tr>
    <tr><td><a href="https://camel.apache.org/docs/" rel="noopener">Apache Camel</a></td><td>The route DSL, EIP implementations, and the Camel Kafka component</td></tr>
    <tr><td><a href="https://camel.apache.org/camel-quarkus/latest/" rel="noopener">Camel Quarkus</a></td><td>The Quarkus runtime for Camel</td></tr>
    <tr><td><a href="https://kafka.apache.org/documentation/" rel="noopener">Apache Kafka</a></td><td>The broker, clients, and topics behind the async patterns</td></tr>
    <tr><td><a href="https://strimzi.io/documentation/" rel="noopener">Strimzi</a></td><td>Kafka on Kubernetes for the final project</td></tr>
    <tr><td><a href="https://www.apicur.io/registry/docs/" rel="noopener">Apicurio Registry</a></td><td>Schema registry and compatibility rules for the async contracts</td></tr>
    <tr><td><a href="https://smallrye.io/docs/smallrye-fault-tolerance/" rel="noopener">SmallRye Fault Tolerance</a></td><td>The @RateLimit annotation and RateLimitException behind the ingest rate-limit guardrail</td></tr>
    <tr><td><a href="https://github.com/networknt/json-schema-validator" rel="noopener">networknt json-schema-validator</a></td><td>In-app JSON Schema validation for the ingest schema-validity guardrail</td></tr>
    <tr><td><a href="https://www.keycloak.org/documentation" rel="noopener">Keycloak</a></td><td>OIDC, PKCE, client_credentials, and the token exchange flows behind the identity guardrails</td></tr>
    <tr><td><a href="https://docs.claude.com/en/docs/claude-code/overview" rel="noopener">Claude Code documentation</a></td><td>Consuming an MCP server and building a Claude Code Skill</td></tr>
    <tr><td><a href="https://docs.ollama.com/" rel="noopener">Ollama</a></td><td>The local model runtime behind the langchain4j consuming examples</td></tr>
    <tr><td><a href="https://opentelemetry.io/docs/specs/semconv/gen-ai/" rel="noopener">OpenTelemetry Semantic Conventions for Generative AI systems</a></td><td>Observability for agent traffic, token and cost attribution</td></tr>
    <tr><td><a href="https://grafana.com/oss/" rel="noopener">Grafana LGTM (Loki, Grafana, Tempo, Mimir)</a></td><td>Traces, metrics and logs for agent traffic</td></tr>
    <tr><td><a href="https://strawberry.rocks/docs" rel="noopener">Strawberry GraphQL</a></td><td>The Python GraphQL framework named in every chapter's cross-stack callout</td></tr>
    <tr><td><a href="https://fastapi.tiangolo.com/" rel="noopener">FastAPI</a></td><td>The Python REST framework named in every chapter's cross-stack callout</td></tr>
  </tbody>
</table>

## Related projects

These are our own companion projects, reused and adapted for layout, infrastructure and precedent.

<table class="bib">
  <colgroup><col style="width:42%"><col style="width:58%"></colgroup>
  <thead><tr><th>Project</th><th>Relationship</th></tr></thead>
  <tbody>
    <tr><td><a href="https://github.com/patterncatalyst/enterprise-integration-patterns-with-camel" rel="noopener">Enterprise Integration Patterns with Camel</a></td><td>The model for this site's pattern-catalog form</td></tr>
    <tr><td><a href="https://github.com/patterncatalyst/event-driven-patterns-with-quarkus-and-camel" rel="noopener">Event-Driven Patterns with Quarkus and Camel</a></td><td>Sibling book; the async patterns in Part 3 build on its Kafka and Camel foundations</td></tr>
    <tr><td><a href="https://github.com/patterncatalyst/cn-patterns-and-practices-grpc" rel="noopener">API Patterns & Practices: gRPC</a></td><td>The reshape-not-wrap pattern (ch 15) defers depth on gRPC-behind-a-facade to this sibling</td></tr>
    <tr><td><a href="https://github.com/patterncatalyst/datamesh-reference-arch-quarkus" rel="noopener">Data Mesh Reference Architecture on Quarkus</a></td><td>Reused by reference for the final project's data mesh</td></tr>
    <tr><td><a href="https://github.com/patterncatalyst/otel-observability-tutorial" rel="noopener">OpenTelemetry Observability Tutorial</a></td><td>OpenTelemetry wiring and Grafana dashboards</td></tr>
  </tbody>
</table>

These titles inform this site's coverage and rigor only. Nothing in these chapters or the runnable examples copies text or code from them.
