---
title: "Building the consuming skill end to end"
order: 43
part: "Appendices"
description: "Walking the committed apa-order-ops Claude Code skill from its anatomy through progressive disclosure to confirming it against a live tool surface."
duration: "20 minutes"
---

An agent staring at a published tool surface still has to work out which tool answers a given
question, what each field in the response means, and what a failure looks like before it
happens. Writing all of that into the tools' own descriptions bloats every call with text most
callers never need. A Claude Code skill solves it differently: a short file an agent reads to
decide whether and how to use a capability, deeper reference material loaded only when a
question calls for it, and a runnable script so the skill is something an agent can operate, not
only read about. `skills/apa-order-ops/` is the skill this book actually ships against the
order-to-delivery surface's three read-only tools, `orderStatus`, `paymentStatus`, and
`shipmentTracking`.

{% include excalidraw.html file="ae-consuming-skill-end-to-end" alt="SKILL.md's name and description stay resident before anything triggers; its one-paragraph body loads once a question matches the description; references/order-operations.md and references/troubleshooting.md load only when that specific question needs them; scripts/order-status.sh runs the live lookup against the apa-orders MCP server outside the agent's context entirely" caption="Figure E.1: The apa-order-ops skill's anatomy and how it loads progressively" %}

## The skill's anatomy

`skills/apa-order-ops/` holds four files: `SKILL.md` at the top, two files under `references/`,
and one under `scripts/`.

`SKILL.md` opens with YAML front matter naming the skill and stating exactly when an agent
should reach for it:

```yaml
---
name: apa-order-ops
description: Use when an agent needs an order's status, payment, or shipment tracking from the apa-orders MCP server, for example "what is the status of order O-9001" or "has O-9001 shipped yet".
---
```

`name` matches the directory the skill lives in; a mismatch there is one of the lint checks the
example's own `verify.sh` runs against the live file, not a description of what a skill should
look like. `description` is the one line Claude Code keeps resident before anything triggers: it
reads every installed skill's front matter up front and decides whether to pull in the rest of a
skill by matching a question against that line alone. Too narrow a description ("look up
O-9001") never matches a different order; too broad a one triggers on questions the skill cannot
answer. The committed line names the three things the skill covers and the server it reads them
from, with two worked phrasings, "what is the status of order O-9001" and "has O-9001 shipped
yet", that should trigger it.

Below the front matter, the body stays to one short paragraph before it reaches any routing
rule: default to the composed `orderStatus` read, call the narrower `paymentStatus` or
`shipmentTracking` only when the composed answer is more than the question needs, never invoke a
tool name `tools/list` does not advertise, and flag a disagreement between an order's status and
its payment or shipment status for a human instead of smoothing it over. That body is also where
the two reference files are named, not duplicated:

- `references/order-operations.md`: every operation's input and output shape, and the field
  values (order, payment, and shipment status, and the two carriers) worth knowing before
  reading a result.
- `references/troubleshooting.md`: what a connection failure, an unknown order id, or an
  unadvertised operation name looks like, and what to do about each.

`scripts/order-status.sh` is the fourth file, and the only one that is runnable rather than
read. One script, not several: it takes an order id and an optional operation name, defaults the
operation to `orderStatus`, and drives the same lifecycle a Claude Code session drives when it
calls the tool directly, `initialize`, `notifications/initialized`, `tools/list`, then
`tools/call`, over `APA_ORDERS_MCP_URL` (`http://localhost:8080/mcp` unless overridden).

## Consuming the tool surface without guessing at it

The script's defining move sits between listing and calling:

```bash
TOOLS_LIST=$(mcp_rpc '{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}')
if ! jq -e --arg n "$OPERATION" '.result.tools[] | select(.name == $n)' <<<"$TOOLS_LIST" >/dev/null; then
    echo "order-status.sh: '$OPERATION' is not advertised by $MCP_URL; refusing to fabricate a result. Advertised operations:" >&2
    jq -r '.result.tools[].name' <<<"$TOOLS_LIST" | sed 's/^/  - /' >&2
    exit 1
fi
```

It checks the live `tools/list` on every run, never a list cached from an earlier session. Ask
for an operation the server has never advertised, a hypothetical `deleteOrder`, and the script
prints what the server actually offers and exits, instead of returning something that looks like
a status. That refusal is what `SKILL.md`'s body already asks of an agent reading the skill
directly: never invoke a tool name `tools/list` does not advertise, and never report a result
that was never returned by the server. The script turns that instruction into a mechanical
check instead of a request for discipline.

A successful call prints the result as plain key-value lines, pulled straight from the tool's
own response text:

```bash
echo "$ORDER_ID ($OPERATION):"
jq -r '.result.content[0].text | fromjson | to_entries[] | "  \(.key): \(.value)"' <<<"$RESULT"
```

`references/order-operations.md` is what tells a reader what to do with those fields once they
come back: an order whose status is `PAID` or later while its payment status is anything but
`COMPLETED`, or one whose status is `SHIPPED` while its shipment status is still `PENDING`, means
two of the three reads disagree about what happened, and the skill asks for that to be flagged
rather than summarized away.

## Progressive disclosure keeps the agent's context small

Splitting the skill into four files instead of one long `SKILL.md` is about what loads when, not
about tidiness. Claude Code keeps every installed skill's `name` and `description` resident, a
couple of lines each, so a session with many skills installed does not carry the weight of all
of them. The body loads once a question's wording matches the description, and in this skill
that body is the one-paragraph routing rule above, not the field tables or the failure catalog.
`references/order-operations.md` and `references/troubleshooting.md` load only when a question
actually needs that depth, one about what a field means, or about why a call just failed, not on
every question the skill answers. Asking "what is the status of O-9001" never needs either
reference file open: the body alone is enough to pick `orderStatus` and call it.

This is also why the body does not re-explain field values or failure modes inline: doing so
would make every triggered question pay for detail only some questions need, the same bloat a
tool's own description risks when it tries to cover every case up front. `scripts/order-status.sh`
sits outside this question entirely. Claude Code invokes it rather than reading it into context,
so the lookup itself never grows the window the reference files are trying to keep small.

## Confirming the skill works

A skill's prose can describe a server that has already moved on; what matters is whether the
words still match what `tools/list` returns and what a reader who runs the script actually sees.

`shellcheck --severity=warning` against `scripts/order-status.sh` is the first check, and the
cheapest: it catches an unquoted expansion or an unset variable before anything talks to a
server. The committed script passes clean at that severity.

A dry run with nothing listening at the configured URL is the second: `order-status.sh O-9001`
against a server that is not up exits non-zero rather than hanging or printing a plausible
answer. Where the TCP connection itself is refused, that failure trips `set -euo pipefail` before
the script's own "is the apa-orders server up" message has a chance to print, so the earliest
visible signal is a bare non-zero exit; where a server answers with something other than HTTP
200, the script's own message names the status code. Either way, the script stops before it
would have to invent anything.

The third check is the one that proves the skill against the thing it describes: bring up the
published tool server (`examples/23-building-a-claude-code-skill`'s compose stack reuses the
same packaged MCP tool server this book's order-to-delivery surface builds, published on
`127.0.0.1:8080`), then run the script against it directly:

```bash
APA_ORDERS_MCP_URL=http://localhost:8080/mcp \
    skills/apa-order-ops/scripts/order-status.sh O-9001
```

The seeded order comes back with its composed status, payment, and shipment fields filled in.
Asking for `deleteOrder` as the operation against that same running server reproduces the
refusal from the dry run, this time with the server's real advertised list printed instead of an
empty one. That example's own `verify.sh` runs a stricter version of the same idea as a
repeatable check: every operation name `references/order-operations.md` documents is asserted
present in the live `tools/list`, `deleteOrder` is asserted absent so that coverage check is not
vacuous, and the script itself is asserted to refuse `deleteOrder` when asked. A skill checked
that way cannot drift from the server silently; a renamed tool or a dropped field fails the next
time the check runs, not the next time an agent happens to ask the wrong question.

## References

- Anthropic, *Equipping agents for the real world with Agent Skills*: [anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills](https://www.anthropic.com/engineering/equipping-agents-for-the-real-world-with-agent-skills)
- Anthropic, *Claude Code documentation*: [docs.claude.com/en/docs/claude-code/overview](https://docs.claude.com/en/docs/claude-code/overview)
- Model Context Protocol specification: [modelcontextprotocol.io](https://modelcontextprotocol.io/specification/2025-11-25)
