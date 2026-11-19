---
title: "Building a Claude Code Skill"
order: 23
part: "Consume"
description: "Packaging the knowledge and helper scripts an agent needs to operate an API as a distributable Claude Code Skill, shipped both as a project skill and as a packaged bundle."
duration: "20 minutes"
---

## Learning objectives

- Package an API's operating knowledge into a Claude Code Skill with a lean `SKILL.md` and progressive-disclosure reference files.
- Ship the same skill over two channels, an in-repo project skill and a packaged bundle, from one source directory.
- Make a skill's helper script refuse an operation a live server does not advertise, rather than fabricate a result for it.
- Recognize undocumented drift between a skill's reference material and a server's real surface as the failure this pattern's coverage check exists to catch.

## Why this matters

An agent operating an API well needs more than a tool call; it needs to know which tool answers which question, what each field in the response means, and what a failure looks like before it happens. Writing all of that into a tool's own description bloats every call with text most callers never need. A Claude Code Skill solves this differently: a short file an agent reads to decide whether and how to use a capability, backed by deeper reference material loaded only when a question actually calls for it, and a runnable script so the skill is something an agent can operate, not only read about.

{% include excalidraw.html file="23-skill-dual-channel" alt="SKILL.md with frontmatter plus references/ and scripts/ is read directly from the repo as a project skill, and the same directory is packaged by package-skill.sh into a .skill zip bundle for distribution elsewhere; order-status.sh checks tools/list before calling an operation and refuses one absent from it" caption="Figure 23.1: One skill directory, two distribution channels, one script that refuses to fabricate" %}

## Pattern: Building a Claude Code Skill

### The problem

A skill written once and never checked against the system it describes drifts the moment that system changes: a renamed operation, a dropped field, a tool that no longer exists all leave the skill's prose untouched while the server underneath moves on. An agent reading a stale skill either fails confusingly against the real server or, worse, a helper script written to "just work" fabricates a plausible-looking answer for an operation that was never actually called, which is worse than a clear failure because nothing signals the answer is wrong.

### The solution

**A skill's `SKILL.md` states when and how to use a capability, its reference files carry detail loaded only when needed, and any script it ships confirms an operation is actually advertised by the live server before calling it, refusing rather than fabricating when it is not.**

### How a Claude Code skill models it

`skills/apa-order-ops/` is the project skill, picked up directly by a Claude Code session started anywhere under the repository. `SKILL.md` carries YAML frontmatter naming the skill and stating when to reach for it:

```yaml
---
name: apa-order-ops
description: Use when an agent needs an order's status, payment, or shipment tracking from the apa-orders MCP server, for example "what is the status of order O-9001" or "has O-9001 shipped yet".
---
```

The body stays short: default to the composed status read, fall back to a narrower one only when the composed response is more than the question needs, and never invoke a tool name `tools/list` does not advertise. Detail that would make `SKILL.md` long lives one level down instead, loaded only when a question actually needs it: `references/order-operations.md` lists each tool's exact input and output fields and what each status value means, and `references/troubleshooting.md` covers what a connection failure or an unadvertised operation name looks like.

`scripts/order-status.sh` is what makes the skill runnable rather than a pile of prose. It opens its own MCP session, checks the requested operation against the live `tools/list`, and only then calls it:

```bash
TOOLS_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
if ! jq -e --arg n "$OPERATION" '.result.tools[] | select(.name == $n)' <<<"$TOOLS_LIST" >/dev/null; then
    echo "order-status.sh: '$OPERATION' is not advertised by $MCP_URL; refusing to fabricate a result. ..." >&2
    exit 1
fi
```

The same directory ships a second way: `package-skill.sh` archives it into a `.skill` zip bundle, a single top-level entry named for the skill, the shape Claude Code expects from an imported skill rather than one read from a project directory. The bundle is built fresh from the project skill's own files on every run; nothing about the packaged channel is a second copy maintained by hand.

The name-coverage witness is what proves the reference material still matches reality: `verify.sh` extracts every operation name `references/order-operations.md` documents and checks each one against the live server's `tools/list`. The negative witness has two halves, proving that check is not vacuous. First, `deleteOrder`, a plausible name an author could mistakenly add to the reference file, is asserted absent from `tools/list`, which is exactly what the coverage check above would catch the moment it were ever documented. Second, `order-status.sh` itself is asked for `deleteOrder` directly and asserted to refuse it:

```bash
order_status_script_refuses_an_unadvertised_operation() {
    ! APA_ORDERS_MCP_URL="$APP/mcp" "$ORDER_STATUS_SCRIPT" O-9001 deleteOrder >/dev/null 2>&1
}
```

That second half matters because the script, not the reference file, is what an agent actually runs. Finally, `package-skill.sh`'s output is checked with `unzip -l`, confirming the packaged bundle really contains the packaged `SKILL.md`, not just that the archiving step ran without error.

This example reuses an existing packaged MCP tool server unchanged; it has nothing of its own to build beyond the skill and the script that operates it.

> **🤖 Explore with your agent**
> **Intent (stack-neutral):** a distributable skill must state when and how to use a capability concisely, push detail into reference material loaded only when needed, and ship a runnable script that refuses to fabricate a result for an operation the live system does not advertise.
> **Contract shape:** operation intent "look up one order's status, payment, or shipment by id"; inputs an order id and an operation name; response shape the operation's own fields on success; error shape a refusal, not a fabricated result, when the operation name is unadvertised or the server rejects the call; idempotency key not needed, every operation here is a read.
> **Build it here:** "Using `skills/apa-order-ops/SKILL.md` and `scripts/order-status.sh` as the model, add a fourth reference section and a corresponding script flag for a new read-only operation, and confirm the name-coverage check would catch it if the operation were ever removed from the server."
> **Build it elsewhere:** "Implement the same pattern in Python/FastAPI (and Strawberry GraphQL): a packaged skill-equivalent bundle (a short instructions file plus a runnable script) whose script checks a live capability list before calling an operation and refuses one absent from it."
> Check: a reference file documenting an operation the live server does not advertise is caught by a coverage check, and the skill's own script refuses that same operation rather than returning a fabricated result for it.

## Build, run, observe

```bash
./verify.sh
```

A fresh stack comes up with the packaged MCP tool server and the shared infrastructure, the lint, exercise, name-coverage, negative, and packaging checks above run against the live server, and the stack is torn down. It ends at 15/15.

Try:

- Ask the skill's script for an order id other than the seeded one and see it report the server's own error instead of a fabricated status.
- Ask for `deleteOrder` as the operation and see the script refuse it, listing what the server actually advertises.
- Unzip the packaged bundle and compare its contents to the project skill directory it was built from.

## Anti-patterns

- **A skill whose reference file drifts from the live server.** Documenting an operation that no longer exists, or missing one that was added, leaves an agent trusting prose that disagrees with the system it describes.
- **A helper script that fabricates a plausible result.** Returning something that looks like an answer for an operation the server never advertised is worse than a clear failure, since nothing signals the answer is wrong.
- **Maintaining the packaged bundle as a hand-edited second copy.** A `.skill` file built once and edited separately from the project skill directory is exactly the kind of fork this pattern's single-source packaging step exists to prevent.

## When not to use it

A capability so small that one short tool description already covers everything a caller needs gains nothing from the extra structure of frontmatter, reference files, and a packaging step; a skill earns its layers only once there is enough standing knowledge, troubleshooting guidance, or runnable tooling to make progressive disclosure worth the indirection.

## References

- Anthropic, *Claude Code documentation*, Skills: [docs.claude.com/en/docs/claude-code/skills](https://docs.claude.com/en/docs/claude-code/skills)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
- Anthropic, *Writing effective tools for AI agents*: [anthropic.com/engineering/writing-tools-for-agents](https://www.anthropic.com/engineering/writing-tools-for-agents)
