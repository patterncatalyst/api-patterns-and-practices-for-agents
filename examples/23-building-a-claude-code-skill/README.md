# 23 Building a Claude Code skill

Packages the knowledge and helper scripts an agent needs to operate the order-to-delivery
surface into a distributable Claude Code Skill, `apa-order-ops`, shipped over two channels at
once: an in-repo project skill at the repo root (`skills/apa-order-ops/`) and a packaged
`.skill` bundle built from that same directory. This example carries no app of its own; it
reuses ch13's `package-api-as-mcp-tools` server unchanged, exactly as
22-consuming-an-mcp-server-from-claude-code does, and verifies the skill against it.

## The skill's structure

`skills/apa-order-ops/` follows the Claude Code Skill format: `SKILL.md` carries YAML
frontmatter (`name: apa-order-ops`, matching the directory; a one-line `description` stating
when to use it) followed by concise markdown instructions for operating the three
`apa-orders` MCP tools. Detail that would make `SKILL.md` long lives one level down instead,
loaded only when needed:

- `references/order-operations.md`: each tool's exact input and output fields, and the order,
  payment, and shipment status values worth knowing before reading a result.
- `references/troubleshooting.md`: what a connection failure, an unadvertised operation name,
  or an unseeded order id looks like.
- `scripts/order-status.sh`: the script that makes the skill runnable rather than a pile of
  prose. It opens its own MCP session against the server, confirms the requested operation
  (`orderStatus` by default, or `paymentStatus` or `shipmentTracking`) is actually advertised,
  calls it, and prints the result.

## Dual-channel distribution

The same directory ships two ways. As a project skill, `skills/apa-order-ops/` is picked up
directly from the repo by a Claude Code session started anywhere under it, the same way
22-consuming-an-mcp-server-from-claude-code's `.mcp.json` is picked up automatically.
As a packaged skill, `package-skill.sh` archives that directory into `apa-order-ops.skill`, a
zip file whose single top-level entry is the skill's own name, the shape Claude Code expects
when a skill is imported rather than read from a project directory. Neither channel forks the
skill's content: the packaged bundle is built fresh from the same files the project skill reads,
never a second copy maintained by hand.

## The lint, exercise, and name-coverage witnesses

`verify.sh` does not test a description of the skill; it tests the skill. It parses `SKILL.md`'s
own frontmatter and asserts `name` and `description` are both present and that `name` equals the
directory name, `apa-order-ops`. It then runs `scripts/order-status.sh O-9001` against the live
server and asserts the output carries the seeded order's real values, `SHIPPED` and tracking
`FL-778821`, the same values ch13's own `verify.sh` asserts. Every operation name
`references/order-operations.md` documents (`orderStatus`, `paymentStatus`,
`shipmentTracking`) is then checked against the live server's `tools/list`: this is the proof
that the skill's documented surface is the real surface, not a copy that has drifted.

That coverage check only matters if it can fail. `deleteOrder`, a plausible name an author could
mistakenly add to the reference file, is asserted absent from `tools/list`, proving the
membership check above would catch it the moment it were ever documented. `order-status.sh`
is then asked for `deleteOrder` directly and asserted to refuse it rather than fabricate a
result, since that script, not the reference file, is what an agent actually runs.

Finally, `verify.sh` runs `package-skill.sh` and asserts the bundle it produces contains
`apa-order-ops/SKILL.md` (`unzip -l` lists it under the packaged skill's own name), proving the
second distribution channel produces a real, importable artifact.

## Run

```bash
./verify.sh
```

A fresh stack comes up (ch13's server plus the shared infrastructure and LGTM), the skill's
lint, exercise, name-coverage, and packaging checks above run, and the stack is torn down. Set
`KEEP=1` to leave the stack running, then run the skill's script by hand or package it yourself:

```bash
KEEP=1 ./verify.sh
../../skills/apa-order-ops/scripts/order-status.sh O-9001
../../skills/apa-order-ops/scripts/order-status.sh O-9001 paymentStatus
./package-skill.sh                        # writes dist/apa-order-ops.skill
```

- Ask for an order id other than `O-9001` and see `order-status.sh` report the server's error
  instead of a fabricated status.
- Ask for `deleteOrder` as the operation and see `order-status.sh` refuse it, listing the
  operations the server actually advertises.
- Open the packaged `.skill` file (`unzip -l dist/apa-order-ops.skill`) and see the same
  `SKILL.md`, `references/`, and `scripts/` the project skill reads.

## Layout

| Path | Purpose |
|------|---------|
| `../../skills/apa-order-ops/SKILL.md` | the skill's frontmatter and instructions, the in-repo project skill channel |
| `../../skills/apa-order-ops/references/order-operations.md` | the three operations' input and output shapes, extracted by `verify.sh`'s name-coverage check |
| `../../skills/apa-order-ops/references/troubleshooting.md` | what a connection failure, an unadvertised operation, or an unseeded order id looks like |
| `../../skills/apa-order-ops/scripts/order-status.sh` | the runnable script: checks the operation is advertised, calls it, prints the result |
| `compose.yaml` | includes the shared infrastructure and builds ch13's server as its own service, published to the host |
| `package-skill.sh` | builds the packaged `.skill` bundle, the second distribution channel |
| `verify.sh` | the lint, exercise, name-coverage, negative, and packaging checks above |
