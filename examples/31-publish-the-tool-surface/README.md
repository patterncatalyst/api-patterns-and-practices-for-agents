# 31 Publish the tool surface

Ships one versioned build of the `orderStatus` / `paymentStatus` / `shipmentTracking` tool
surface as three artifacts, all cut from the SAME image: `quarkus-mcp-server-http` over
Streamable HTTP, `quarkus-mcp-server-stdio` driven over stdin/stdout with `docker run -i`, and a
served `/llms.txt`. The tools themselves read a fixed, in-memory seeded order (`O-9001`), not a
database; the point of this example is that the version string agrees everywhere, not the reads
behind the tools, which ch13 already covers.

## The single source of truth

`application.properties` sets:

```properties
quarkus.mcp.server.server-info.version=${quarkus.application.version}
```

`quarkus.mcp.server.server-info.version` is a real runtime config key in the
`quarkus-mcp-server-core` 2.0.4 jar: its own `quarkus-config-doc` metadata lists
`quarkus.mcp.server.server-info.version` (environment variable
`QUARKUS_MCP_SERVER_SERVER_INFO_VERSION`), and when it is absent the extension already falls back
to `quarkus.application.version` on its own. It is set here explicitly rather than left to that
fallback, so the property that controls `serverInfo.version` is visible in one place instead of
relying on undocumented default behaviour. `quarkus.application.version` is Quarkus's own compiled
copy of the Maven `${project.version}` from `pom.xml`; nothing in this example sets it by hand.

`ToolSurfaceMetadata` reads the identical `quarkus.application.version` property for `/llms.txt`.
One property, two readers (`quarkus-mcp-server`'s own handshake code, and `ToolSurfaceMetadata`),
so there is no second version string to let drift.

## One image, two transports

The Containerfile builds one fast-jar. The compose service runs it detached, which is what
Streamable HTTP needs (a published, reachable port) and what a detached container has: no stdin.
`docker run -i` runs the SAME image attached, with `QUARKUS_MCP_SERVER_STDIO_ENABLED=true`, which
needs no published port at all. `%prod.quarkus.mcp.server.stdio.enabled=false` keeps stdio off in
the compose service (where a detached container's immediate stdin EOF would shut it down a few
seconds after start, ch12's and ch13's fix); the environment variable overrides that per
invocation, turning stdio back on for the one-shot without a second build.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up, the image is built once and tagged `publish-the-tool-surface:$VERSION`
(`$VERSION` read once from `pom.xml`), then the SAME tag is driven two ways: the compose
container's Streamable HTTP `initialize` and `tools/list`, and a `docker run -i` stdio one-shot
piping the same JSON-RPC sequence into stdin. Both report `serverInfo.version` equal to
`$VERSION` and both advertise the tool set committed in `contracts/31-order-tools.mcp.json`; the
served `/llms.txt` is checked against `contracts/31-llms.txt` and must carry the same version.

- Try `curl -s http://localhost:8089/llms.txt` after a run and compare it to the committed file.
- Try piping the same `initialize` / `notifications/initialized` / `tools/list` sequence into
  `docker run -i --rm -e QUARKUS_MCP_SERVER_STDIO_ENABLED=true publish-the-tool-surface:$VERSION`
  by hand and read `serverInfo.version` out of the first response line.
- Try `-e QUARKUS_MCP_SERVER_SERVER_INFO_VERSION=9.9.9` on that same `docker run` and watch the
  reported version disagree with every other surface, which is the check `verify.sh` bites on.

## The negative witness

`verify.sh` runs a second stdio one-shot against the identical image with
`QUARKUS_MCP_SERVER_SERVER_INFO_VERSION=9.9.9` set. That invocation reports `serverInfo.version`
as `9.9.9`, not `$VERSION`, which is exactly what the three-way agreement check would catch: an
agent that trusted this invocation's handshake would believe it was talking to a different build
than the one `/llms.txt` and the HTTP handshake agree on. A normal stdio one-shot run immediately
afterwards reports `$VERSION` again, confirming the override was per-invocation, not a persisted
drift in the image itself.

A reproducible source-edit bite: hardcode the `Version: ` line in `LlmsTxtResource.llmsTxt()` to a
literal string instead of reading `ToolSurfaceMetadata.version()`, rebuild, and rerun `verify.sh`.
The HTTP and stdio handshakes still agree with each other and with `$VERSION`; only `/llms.txt`
disagrees, which is what `llms_txt_carries_the_pom_version` and
`all_three_versions_agree` exist to catch. Restoring the call to `metadata.version()` returns
`/llms.txt` to byte-identical with the committed contract and every check passes again.
