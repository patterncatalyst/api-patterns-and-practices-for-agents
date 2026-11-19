---
title: "Publish the Tool Surface"
order: 31
part: "Ship"
description: "One Quarkus build serves an MCP tool surface over Streamable HTTP and stdio, and publishes the same surface as llms.txt, so the version string can never drift between the three."
duration: "20 minutes"
---

## Learning objectives

- Compile one build's own version into every surface it serves, instead of maintaining the same string by hand in more than one place.
- Confirm a Quarkus MCP server configuration key is real by reading the extension's own packaged configuration metadata, before relying on it.
- Drive the identical fast-jar over Streamable HTTP and over stdin/stdout, and tell which transport a given caller actually needs.
- Recognize a disagreeing version string as a sign that one artifact was built, or run, separately from the others.

## Why this matters

An agent that calls a tool surface over a network handshake, and separately reads a published discovery document describing that same surface, assumes both describe one running build. When the two are built, tagged or deployed on separate schedules, that assumption breaks quietly: the handshake reports one version, the discovery document reports another, and neither response says which one is stale. An agent has no way to ask "are you the same build as that other endpoint"; it can only compare whatever version strings each surface volunteers, and those strings agree only by coincidence once two separate release steps are what set them.

{% include excalidraw.html file="31-one-version-three-artifacts" alt="One Quarkus build compiles its own version in once; the Streamable HTTP handshake, the identical jar run over stdio, and a served llms.txt all read that one property, so the three artifacts report the same version by construction" caption="Figure 31.1: One build, three artifacts, one version property" %}

## Pattern: Publish the Tool Surface

### The problem

Shipping a tool surface as independent artifacts, an image built for a network handshake, a jar packaged separately for a directly attached process, and a hand-written discovery document, invites each one to drift from the others. A release of the network-facing image without the hand-written document updated behind it, or a stdin/stdout jar cut from an older commit than the detached service answering the same handshake, each leaves two artifacts naming a version that no longer matches what the third serves. Nothing forces the three back into agreement; an agent trusting any single one of them has no second source to check it against.

### The solution

**Build one artifact, run the identical jar as both a detached network service and a stdin/stdout one-shot, and compile the single version property both report into the same discovery document a caller reads before ever calling a tool.**

### How Quarkus models it

`quarkus-mcp-server`'s own packaged configuration metadata names a real runtime key for the handshake's reported version, confirmed by inspecting the installed extension jar directly rather than guessing from its documentation. Pointing that key at the build's own compiled-in version, rather than a hand-typed string, is what makes the handshake and everything else reading the same property agree automatically:

```properties
quarkus.mcp.server.server-info.version=${quarkus.application.version}
```

`quarkus.application.version` is Quarkus's own compiled copy of the Maven project version; nothing in the source sets it by hand. A served discovery document reads the identical property, so there is exactly one place a release changes the version, not two:

```java
@ConfigProperty(name = "quarkus.application.version")
String version;
```

Both transports come from the same `@Tool`-annotated bean, never two separate builds. A detached service needs a reachable network port and no stdin at all; a directly attached process (`docker run -i`, or a plain `java -jar`) needs no published port but does need its standard input left open for the handshake and every subsequent call. `quarkus.mcp.server.stdio.enabled` is false only in the profile serving the detached container, where a closed standard input would otherwise read end-of-file and shut the process down moments after start; an environment variable overrides that flag per invocation, turning stdin-driven access on for exactly the one-shot that needs it, with no second build and no second image. Overriding the handshake's version property by that same per-invocation mechanism, without touching the source at all, reports a different string from one invocation alone: every other invocation of the identical image still reports the build's own version, which is what makes a disagreement between the two traceable to the invocation, not the build.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** whichever way a caller reaches a tool surface, a network handshake, a locally spawned process, or a published discovery document, all of them must report the same version string, because that string is how a caller tells whether two surfaces describe one running build.
> **Contract shape:** a version field present on every surface a tool-serving build exposes, each one read from the single place the build's own version is set, never copied by hand into a second place.
> **Build it here:** "Using `ToolSurfaceMetadata` and the `quarkus.mcp.server.server-info.version` property as the model, add a fourth surface, for example a health-check body carrying the same version field, and confirm it reads the identical property rather than a new hardcoded string."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): one build's own package version, set in exactly one place, reported identically by an MCP server's initialize handshake, a command-line one-shot's own startup output, and a served discovery document."
> Check: overriding one surface's reported version by itself, without rebuilding, is the only way to make the surfaces disagree; every other invocation of the same build reports the identical string.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, one image is built and tagged once, and that same tag is driven two ways: a detached network handshake and a `docker run -i` stdin/stdout one-shot. Both are checked against the same committed tool-surface contract and the same version, the served discovery document is checked against its own committed contract, and a one-shot run with the version overridden by environment confirms the three-way agreement actually breaks for that invocation before a normal run afterward restores it. It ends at 17/17.

- Try overriding the handshake's version by environment on a stand-alone stdin/stdout invocation and compare its reported version to the detached service's own.
- Try fetching the discovery document after a run and compare its version line to the handshake's.
- Try hardcoding the discovery document's version as a literal string instead of reading it from configuration and watch the agreement check fail.

## Anti-patterns

- **Hand-maintaining a version string in a discovery document separately from the build that serves it.** The string is correct on the day someone remembers to update it and wrong on every day after a release that forgets.
- **Building two separate images for a network handshake and a stdin/stdout one-shot from the same source.** Each build step is a chance for the two to be cut from different commits, correct individually and silently mismatched together.
- **Trusting a single surface's version report with nothing to check it against.** One number, reported once, is an assertion; the same number reported identically by a second, independently served surface is the only thing that makes it a fact.

## When not to use it

A tool surface served from exactly one transport, with no second artifact and no discovery document published alongside it, has nothing to drift from: there is only one place the version lives, so compiling it into a second property buys agreement that was never at risk. The pattern earns its cost only once a second artifact exists and a caller might reasonably compare the two.

## References

- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- llms.txt: [llmstxt.org](https://llmstxt.org/)
- Quarkus MCP Server: [docs.quarkiverse.io/quarkus-mcp-server/dev/index.html](https://docs.quarkiverse.io/quarkus-mcp-server/dev/index.html)
