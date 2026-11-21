// deck.js: "Running Agent APIs in Production"
// Deck 3 of 3 companion decks for API Patterns and Practices: APIs for Agents and LLMs.
//
// Build:  node deck.js   (run from presentation/running-agent-apis-prod)
// Paths:  deck-helpers.js reads DECK_ASSETS (./assets) and DECK_PNG (./png).

"use strict";

const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, ASSETS,
  newDeck, addFooter, addContentTitle, addBullets, addTwoColBullets,
  addStatusTable, addCaption, addCodeSlide, addDiagramSlide, addSectionDivider, addNotes,
} = H;

const OUT = "./running-agent-apis-prod-r17.0.pptx";
const REV = "r17.0";

const pres = newDeck();
pres.title = "Running Agent APIs in Production";
pres.author = "Pattern Catalyst";
pres.company = "Pattern Catalyst";
pres.subject = "API Patterns & Practices: APIs for Agents & LLMs";

let pageNum = 0;

function S() {                       // new content slide with footer (page no. + logo)
  const s = pres.addSlide(); pageNum += 1; addFooter(s, pageNum); return s;
}
function divider(code, title, subtitle, notes) {   // section divider slide
  const s = pres.addSlide(); pageNum += 1; addSectionDivider(s, code, title, subtitle); addNotes(s, notes);
}

// ---- Cover --------------------------------------------------------------
{
  const s = pres.addSlide();
  pageNum += 1;
  s.background = { color: COLOR.white };
  try { s.addImage({ path: `${ASSETS}/cover-panel.png`, x: 0, y: 0, w: W, h: 7.5 }); } catch (e) {}
  s.addText("API PATTERNS AND PRACTICES FOR AGENTS AND LLMS", { x: 6.00, y: 1.98, w: 6.90, h: 0.34,
    fontFace: FONT.title, fontSize: 14, bold: true, color: COLOR.red, charSpacing: 6, align: "left", valign: "middle" });
  s.addText([{ text: "Running Agent APIs", options: { breakLine: true } }, { text: "in Production" }], {
    x: 5.95, y: 2.42, w: 6.95, h: 2.00, fontFace: FONT.title, fontSize: 50, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("Consume, test, ship, run, and the focused multi-domain data mesh.", { x: 6.00, y: 4.65, w: 6.70, h: 0.90,
    fontFace: FONT.body, fontSize: 18, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 5.85, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.80, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s, "This is the third of three companion decks for API Patterns and Practices: APIs for Agents and LLMs. "
    + "It covers the consumer side of an agent-facing API, the test harnesses that catch drift a health check misses, "
    + "the shipping and CI gates that block a bad release before an agent ever calls it, the production guardrails "
    + "an agent-facing surface needs, and closes on the final project: a focused multi-domain data mesh. Every pattern "
    + "shown here runs against a real Quarkus and Camel stack and is verified live, not assumed from documentation.");
}

// ===========================================================================
// SECTION 01: CONSUME
// ===========================================================================
divider("01", "Consume", "Five ways a human, a script, or another agent drives an agent-facing API",
  "This section covers the consumer side: a human in Claude Code, a packaged Skill, a programmatic langchain4j agent, "
  + "a Kafka consumer waking on an event, and a deterministic reviewer gating an agent's proposed action. Each pattern "
  + "answers the same underlying question: what exactly is a consumer allowed to drive, and who decides whether its "
  + "proposal takes effect. None of these depend on the service under consideration being changed; each reuses an "
  + "already-built agent-facing service unchanged.");

// --- 22: Consuming an MCP Server from Claude Code -------------------------
{
  const s = S();
  addContentTitle(s, "CONSUME · MCP FROM CLAUDE CODE", "A transcript proves one conversation, not a contract");
  addBullets(s, [
    "An interactive Claude Code session against a packaged MCP server proves a question got answered once, by one person, at one moment.",
    "A transcript has no pass or fail. Reading it back shows what happened, never whether it was correct.",
    "A scripted client runs the identical connect, discover, call lifecycle, initialize, tools/list, resources/list, prompts/list, every time, against whatever the server advertises right now.",
    "A call to a tool absent from tools/list must be rejected, by any client, scripted or interactive. That rejection is the real boundary of what a consumer can drive.",
  ], { fontSize: 17 });
  addNotes(s, "A committed .mcp.json registers the packaged order-to-delivery MCP server for a Claude Code session, under a name "
    + "and a Streamable HTTP URL, no further wiring needed. mcp-client.sh is the deterministic stand-in for that session: a "
    + "bash, curl and jq script performing the exact lifecycle a client runs, then a tools/call for each advertised tool "
    + "against a seeded order. The tool-coverage witness asserts tools/list advertises exactly the expected set, diffed "
    + "against a committed expected-tools file. The negative witness calls deleteOrder directly, a name the server never "
    + "advertises, and asserts it comes back rejected. A sanitized, real Claude Code session, captured non-interactively "
    + "and filtered of any machine identifier, confirms the same lifecycle produces the same answer from an actual client.");
}
{
  const s = S();
  addDiagramSlide(s, "CONSUME · MCP FROM CLAUDE CODE", "The same client lifecycle, scripted and human",
    "22-mcp-client-lifecycle",
    "Claude Code and a scripted JSON-RPC client drive the identical lifecycle; an unadvertised tool is rejected either way.");
  addNotes(s, "Claude Code picks up the committed .mcp.json and negotiates initialize, tools/list, resources/list and "
    + "prompts/list against the packaged MCP tool server. A scripted client drives the identical lifecycle as a "
    + "deterministic, repeatable check. The diagram's key point is symmetry: whichever client runs the handshake, a call "
    + "to deleteOrder, a name the server never advertises, is rejected the same way. That is what makes tools/list the "
    + "actual boundary of what a consumer can drive, not a convention a client happens to honor.");
}

// --- 23: Building a Claude Code Skill --------------------------------------
{
  const s = S();
  addDiagramSlide(s, "CONSUME · CLAUDE CODE SKILLS", "One skill directory, two distribution channels",
    "23-skill-dual-channel",
    "A project skill read in place and a packaged .skill bundle both come from the same source directory.");
  addNotes(s, "skills/apa-order-ops is the project skill, picked up directly by a Claude Code session started anywhere "
    + "under the repository. SKILL.md carries frontmatter naming the skill and when to reach for it, and stays short by "
    + "pushing detail into references/order-operations.md and references/troubleshooting.md, loaded only when a question "
    + "actually needs them. package-skill.sh archives the identical directory into a .skill zip bundle on every run, so "
    + "nothing about the packaged channel is a second copy maintained by hand. Explain that progressive disclosure is the "
    + "point: a short file an agent reads to decide whether and how to use a capability, with the deep material one level down.");
}
{
  const s = S();
  addCodeSlide(s, "CONSUME · CLAUDE CODE SKILLS", "A skill script that refuses rather than fabricates", "bash",
    [
      "TOOLS_LIST=$(mcp_rpc '{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}')",
      "if ! jq -e --arg n \"$OPERATION\" \\",
      "    '.result.tools[] | select(.name == $n)' <<<\"$TOOLS_LIST\" >/dev/null",
      "then",
      "    echo \"refusing to fabricate a result for '$OPERATION'\" >&2",
      "    exit 1",
      "fi",
    ],
    "order-status.sh checks the live tools/list before calling an operation, and refuses one absent from it.");
  addNotes(s, "The name-coverage witness extracts every operation references/order-operations.md documents and checks each "
    + "one against the live server's tools/list, proving the reference material still matches reality. The negative "
    + "witness has two halves: deleteOrder is asserted absent from tools/list, and order-status.sh itself is asked for "
    + "deleteOrder directly and asserted to refuse it. That second half matters because the script, not the reference "
    + "file, is what an agent actually runs. A helper script that fabricates a plausible-looking answer for an operation "
    + "the server never advertised is worse than a clear failure, because nothing signals the answer is wrong.");
}

// --- 24: LangChain4j as a Programmatic Consumer ----------------------------
{
  const s = S();
  addDiagramSlide(s, "CONSUME · LANGCHAIN4J PROGRAMMATIC CONSUMER", "The record comes from the captured tool result, not the model's retelling",
    "24-captured-tool-result",
    "The durable record is read from the framework's captured tool execution, never from the model's own prose.");
  addNotes(s, "OrderStatusConsumerAgent is a local langchain4j AiService, run unattended with no human in the loop. "
    + "@McpToolBox registers the packaged MCP server's tools as remote tools this one method may call over Streamable "
    + "HTTP, with nothing hand-rolling an HTTP client or parsing a JSON-RPC envelope. The method returns Result<String>, "
    + "not a bare String, specifically so a caller can read toolExecutions, the exact text the remote tool returned, "
    + "captured by the framework the moment the call completed. A model's summary of a tool result can compress, "
    + "misstate, or omit a field; a shipment tracking number does not tolerate that kind of paraphrase.");
}
{
  const s = S();
  addCodeSlide(s, "CONSUME · LANGCHAIN4J PROGRAMMATIC CONSUMER", "Read the captured result, never the model's reply", "Java · Quarkus",
    [
      "Result<String> result = agent.consume(\"Get the status of order \" + orderId + \".\");",
      "String statusText = result.toolExecutions().stream()",
      "        .filter(e -> \"orderStatus\".equals(e.request().name()))",
      "        .map(ToolExecution::result)",
      "        .reduce((first, last) -> last)",
      "        .orElse(\"\");",
      "recorder.recordConsultation(orderId, statusText);",
    ],
    "Filtering on the tool call's own name is what makes the model's final sentence irrelevant to the record.");
  addNotes(s, "OrderStatusRecorder parses the order status and tracking number out of this captured text, server side, "
    + "before the row is written; a nondeterministic model cannot change what gets recorded as long as it makes the "
    + "call. ConsumerBean always calls recordConsultation after calling the agent, whether the model reached the remote "
    + "tool or not, so a row's presence alone is not proof the round trip happened; only a value the remote tool alone "
    + "could have supplied is. A fixture path, forced in tests, calls the same remote tool directly through a hand-built "
    + "MCP client, bypassing the model entirely, and reaches the identical recordConsultation call.");
}

// --- 25: Consuming the Async Side ------------------------------------------
{
  const s = S();
  addDiagramSlide(s, "CONSUME · THE ASYNC SIDE", "A Kafka consumer wakes the agent; the record is keyed so a repeat is a no-op",
    "25-consuming-the-async-side",
    "A disabled consumer leaves an event sitting unconsumed, never lost; a redelivered event resolves to the same row.");
  addNotes(s, "ShipmentDeliveredRoute is the trigger, nothing more: a Camel Kafka consumer that unmarshals the event and "
    + "calls a composing agent. consumer.async.enabled stops the route outright when set false; a Kafka consumer group "
    + "never loses a message by being stopped, it only delays reading one. NotificationComposerAgent carries no tool at "
    + "all, its only job is turning the event into a sentence a customer can read. The order id, customer id, and event "
    + "type that make up the durable Notification come straight from the consumed event, built before the agent is ever "
    + "called; only the message text passes through the model.");
}
{
  const s = S();
  addCodeSlide(s, "CONSUME · THE ASYNC SIDE", "A conflict guard keyed on the event's own identity", "SQL",
    [
      "INSERT INTO notify.notifications",
      "    (id, order_id, event_type, customer_id, message)",
      "VALUES (?, ?, ?, ?, ?)",
      "ON CONFLICT (order_id, event_type) DO NOTHING",
    ],
    "(order_id, event_type) is the same pair that identifies the event itself.");
  addNotes(s, "At-least-once delivery means a real event can arrive more than once, and a notification sent twice for "
    + "one delivery is worse than one sent late. The same ShipmentDelivered event delivered twice, from a rebalance or a "
    + "retrying producer, resolves to the same key, and the second insert is a no-op. A fixture path composes a "
    + "deterministic templated message instead of calling a model in tests, reaching the identical record call, so the "
    + "row, counter, and log line a check reads are indistinguishable whichever path produced them. A health check "
    + "against the application as a whole, rather than the route's own running state, would report healthy while every "
    + "event on the topic sat unconsumed.");
}

// --- 26: Decision-Rule Review -----------------------------------------------
{
  const s = S();
  addDiagramSlide(s, "CONSUME · DECISION-RULE REVIEW", "The proposal varies; the committed rule and its verdict do not",
    "26-decision-rule-review",
    "Removing the amount-ceiling clause flips the identical violating proposal from REJECT to APPROVE.");
  addNotes(s, "A model asked to both propose an action and judge its own proposal is marking its own work. "
    + "DecisionRuleReviewer loads a committed rule once at startup and evaluates clauses in order, returning the first "
    + "one a proposal fails. The rule itself is committed as data, not code: an ordered list of clauses covering the "
    + "refund amount against the paid amount, the order's refundable status, and an allowed set of reasons. No model is "
    + "on this review path at all; since the reviewer is deterministic by construction, a live model call would add "
    + "nothing to the example's own assertions and would undercut the lesson the gate exists to teach.");
}
{
  const s = S();
  addCodeSlide(s, "CONSUME · DECISION-RULE REVIEW", "A weakened clause flips the same proposal from reject to approve", "Java · Quarkus",
    [
      "static Verdict evaluate(DecisionRule rule, RefundProposalRun run) {",
      "    for (RuleClause clause : rule.clauses()) {",
      "        if (!holds(clause, run)) {",
      "            return Verdict.reject(clause.id());",
      "        }",
      "    }",
      "    return Verdict.approve();",
      "}",
    ],
    "Pointing the same reviewer at the rule with the amount-ceiling clause removed wrongly approves the violation.");
  addNotes(s, "The weakened-reviewer witness is a second committed rule file, the same three clauses with the "
    + "amount-ceiling clause removed, read only when review.rule.path is overridden to point at it. Pointed at the "
    + "weakened file and asked to review the exact same violating proposal again, the reviewer wrongly approves it. "
    + "Restoring the real rule path and replaying the identical proposal rejects it again, naming the same clause it "
    + "named the first time. That flip, caused by nothing except which rule file was read, proves the amount-ceiling "
    + "clause, not some other property of the proposal, was catching the over-refund.");
}

// ===========================================================================
// SECTION 02: TEST AND AUTOMATE
// ===========================================================================
divider("02", "Test and automate", "A health check proves a process started. These checks prove the contract held.",
  "This section covers four harnesses that each catch a failure a readiness probe cannot see: a running service "
  + "drifting from its own published contract, an agent's behaviour degrading in a way only structured signals expose, "
  + "a scripted consumer whose error paths regress silently behind a passing exit code, and an async, human-gated action "
  + "whose only real outcome lands as a durable effect long after the trigger returns. Every one of these is proven to "
  + "bite with a committed negative witness, not assumed to work because it has never failed.");

// --- 27: Contract and Manifest Conformance ----------------------------------
{
  const s = S();
  addDiagramSlide(s, "TEST · CONTRACT AND MANIFEST CONFORMANCE", "A readiness probe never reads a line of the contract",
    "27-contract-manifest-conformance",
    "A conformance check compares what a service actually serves against what it commits to.");
  addNotes(s, "Four checks run against a reused strict self-describing REST service and a packaged MCP tool server. "
    + "Schemathesis generates requests straight from the committed OpenAPI document and asserts status codes, content "
    + "types, and response schemas conform. A second check diffs the service's own served OpenAPI document against the "
    + "committed one. A third and fourth check diff the served MCP tools list against the committed manifest. "
    + "Conformance checking a running service, not just its startup, caught three real gaps during this harness's own "
    + "build: an out-of-range value, a malformed body, and a missing required field, each an undocumented 500 a health "
    + "probe would have called healthy the entire time.");
}
{
  const s = S();
  addCodeSlide(s, "TEST · CONTRACT AND MANIFEST CONFORMANCE", "Generate requests straight from the committed contract", "bash",
    [
      "docker run --rm --network apa -v \"<repo>/contracts:/contracts:ro\" \\",
      "  schemathesis/schemathesis:4.33.3 run /contracts/order-service.openapi.yaml \\",
      "  -u http://strict-self-describing-contract:8080 \\",
      "  -c status_code_conformance,content_type_conformance,response_schema_conformance",
    ],
    "Schemathesis runs as a one-shot container on the service's own Docker network, by its compose service name.");
  addNotes(s, "None of the four checks mean anything unless they can fail. The harness proves it with a committed, "
    + "drifted copy of the OpenAPI contract and a committed, drifted copy of the MCP manifest: the drifted document "
    + "tightens a response schema and removes two documented error responses, and the drifted manifest renames a tool "
    + "and drops a required parameter. The exact same Schemathesis run and the exact same tools-list diff are run "
    + "against those drifted fixtures, and both must fail, with no change to either reused service. A check that has "
    + "never been seen to fail against a known-bad contract might be passing for the wrong reason.");
}

// --- 28: Agent-Behaviour Eval with Deterministic Replay ---------------------
{
  const s = S();
  addDiagramSlide(s, "TEST · AGENT-BEHAVIOUR EVAL", "Score the transcript, never the model's account of it",
    "28-agent-behaviour-eval",
    "A live run and a replayed fixture produce the same transcript shape, scored by the identical evaluator.");
  addNotes(s, "BehaviourEvaluator loads a committed eval spec once at startup and checks a transcript as six ordered "
    + "assertions, stopping at the first one violated: the expected tool sequence, a call budget, an argument-error "
    + "budget, a token budget, whether the refund actually completed, and the final order status. Every one of those "
    + "reads a captured, structured field; a free-text reason captured alongside a tool call is kept for the audit "
    + "record but never asserted against, because free text varies even on a fully compliant run. A committed "
    + "regressed-run fixture scores FAIL naming the first problem the ordered assertions reach; a committed good-run "
    + "fixture scores PASS.");
}
{
  const s = S();
  addCodeSlide(s, "TEST · AGENT-BEHAVIOUR EVAL", "Six ordered assertions, stop at the first one violated", "Java · Quarkus",
    [
      "static EvalVerdict evaluate(EvalSpec spec, Transcript t) {",
      "    if (!onlyExpectedToolsWereCalled(spec, t))",
      "        return EvalVerdict.fail(\"unexpected-tool-called\");",
      "    if (t.toolCalls().size() > spec.maxToolCalls())",
      "        return EvalVerdict.fail(\"call-budget-exceeded\");",
      "    // ...arg-error budget, token budget, refund outcome, final status",
      "    return EvalVerdict.pass();",
      "}",
    ],
    "Editing only the recorded outcome of a passing transcript flips the verdict, naming the assertion that now fails.");
  addNotes(s, "LiveRunner drives a local langchain4j AiService through its one tool once, then builds the identical "
    + "Transcript shape from the captured Result.toolExecutions, never from anything the model said about its run. "
    + "BehaviourEvaluator scores that transcript exactly the same way it scores a replayed fixture, so the default "
    + "build pipeline runs the fixture path with no model required at all, and an operator who wants to see the real "
    + "model's current behaviour sets a single flag to drive the live path against the same spec. An eval spec that has "
    + "only ever scored a compliant transcript has never been seen to catch anything.");
}

// --- 29: Scripted Non-Interactive Consumption -------------------------------
{
  const s = S();
  addDiagramSlide(s, "TEST · SCRIPTED CONSUMPTION", "Judge the report, never the runner's exit code",
    "29-scripted-non-interactive",
    "A pinned Newman container drives the committed collection; the JSON report's assertion counts are what matter.");
  addNotes(s, "The collection walks four moves against a reused strict self-describing REST service: place an order and "
    + "get a 201, read it back and get a 200 with a PLACED status, ask for an order that does not exist and get a 404 "
    + "with an application/problem+json body, and submit an invalid quantity and get a 400 problem document. Newman, a "
    + "pinned and maintained open source runner, runs as a one-shot container reaching the service by compose service "
    + "DNS, never the host port. The run is judged by the JSON report's pass and fail counts, read with jq; the runner's "
    + "own exit code is left unread.");
}
{
  const s = S();
  addCodeSlide(s, "TEST · SCRIPTED CONSUMPTION", "The exit code is never read", "bash",
    [
      "unknown_order_404_assertion_held() {",
      "    jq -e --arg name \"$UNKNOWN_ORDER_REQUEST\" '",
      "        [.run.executions[] | select(.item.name==$name) | .assertions[]?] as $a",
      "        | ($a|length) > 0 and (all($a[]; .error == null))",
      "    ' \"$REPORT\" >/dev/null",
      "}",
    ],
    "An unknown order answered as a 500 instead of a 404 problem+json turns this assertion from pass to fail.");
  addNotes(s, "When the service's error handling drifts, its exception mappers removed so an unknown order falls "
    + "through to a 500 with a plain-text body, the status and content-type assertions on that request gain an error in "
    + "the report, and this check turns from pass to fail; the drift is observable from the report alone. A test "
    + "harness that trusts the exit code is trusting the runner to have noticed every deviation and signalled it the "
    + "way the harness expects, and a drifted error path can slip through that gap entirely while the process still "
    + "exits zero.");
}

// --- 30: Testing Async Flows and the HITL Gate ------------------------------
{
  const s = S();
  addDiagramSlide(s, "TEST · ASYNC FLOWS AND THE HITL GATE", "The 202 is the same in every case. The durable effect is not.",
    "30-async-effect-observation",
    "The harness polls durable rows and topic offsets; a dropped decision leaves no effect across a bounded window.");
  addNotes(s, "A human-in-the-loop cancel-and-refund gate has no synchronous return value worth asserting on: the "
    + "trigger accepts the request and holds, so a test on its 202 passes whether the gate held the action, executed it "
    + "once, rejected it, or lost the decision entirely. Four scenarios exercise the gate: a trigger opens a PENDING "
    + "gate, an APPROVE resumes it to executed exactly once even when redelivered, a REJECT terminates it with no cancel "
    + "and no refund, and a dropped decision is the biting negative witness, where nothing should ever happen and the "
    + "harness confirms nothing does across the window.");
}
{
  const s = S();
  addCodeSlide(s, "TEST · ASYNC FLOWS AND THE HITL GATE", "holds_for proves an absent effect, not just a present one", "bash",
    [
      "poll() { # retry every 2s until success, or fail at the deadline",
      "    local end=$((SECONDS + $1)); shift",
      "    until \"$@\"; do (( SECONDS < end )) || return 1; sleep 2; done",
      "}",
      "holds_for() { # the command must stay true for the whole window",
      "    local end=$((SECONDS + $1)); shift",
      "    until (( SECONDS >= end )); do \"$@\" || return 1; sleep 2; done",
      "}",
    ],
    "A dropped decision is the absence of an effect, exactly what an exit-code test cannot see.");
  addNotes(s, "When the gate is removed from the subject, so the trigger cancels and refunds immediately, the pause "
    + "scenario's no-side-effect-before-a-decision assertions fail, because the order is cancelled and the payment "
    + "refunded the instant the trigger returns, and no pending-decision record is ever published. An approve-by-default "
    + "wait that treats an unanswered decision as an approval after an interval cannot tell a held gate from an "
    + "executed one, and hides exactly the dropped-decision case this harness exists to catch. The gate row, the "
    + "subject rows, and the topic offsets are the only places the real outcome ever shows up.");
}

// ===========================================================================
// SECTION 03: SHIP
// ===========================================================================
divider("03", "Ship", "One build, three artifacts, and two CI gates before anything merges",
  "This section covers shipping a tool surface without letting its artifacts drift apart, and gating a merge on two "
  + "independent promises an agent-facing service makes: a request-response contract a caller builds a client from, and "
  + "an event schema a producer and a consumer agree on independently of either one's release schedule. Both gates read "
  + "a real tool, a conformance runner and a schema registry's own rule engine, rather than a hand-rolled approximation of either.");

// --- 31: Publish the Tool Surface -------------------------------------------
{
  const s = S();
  addDiagramSlide(s, "SHIP · PUBLISH THE TOOL SURFACE", "One version property, three artifacts",
    "31-one-version-three-artifacts",
    "The Streamable HTTP handshake, the same jar over stdio, and a served llms.txt all read the one compiled-in version.");
  addNotes(s, "An agent that calls a tool surface over a network handshake, and separately reads a published discovery "
    + "document for that same surface, assumes both describe one running build. quarkus-mcp-server's packaged "
    + "configuration metadata names a real runtime key for the handshake's reported version, confirmed by inspecting "
    + "the installed extension jar directly. Pointing that key at quarkus.application.version, the build's own compiled "
    + "project version, is what makes the handshake and the discovery document agree automatically, with exactly one "
    + "place a release changes the version, not two.");
}
{
  const s = S();
  addCodeSlide(s, "SHIP · PUBLISH THE TOOL SURFACE", "Compile the version in once", "Properties + Java · Quarkus",
    [
      "# application.properties",
      "quarkus.mcp.server.server-info.version=${quarkus.application.version}",
      "",
      "@ConfigProperty(name = \"quarkus.application.version\")",
      "String version;",
    ],
    "Overriding one surface's version by itself is the only way to make the three artifacts disagree.");
  addNotes(s, "Both transports come from the same @Tool-annotated bean, never two separate builds. A detached service "
    + "needs a reachable network port and no stdin; a directly attached process needs no published port but needs its "
    + "standard input left open. quarkus.mcp.server.stdio.enabled is false only in the profile serving the detached "
    + "container; an environment variable overrides that flag per invocation, turning stdin-driven access on for the "
    + "one-shot that needs it, with no second build and no second image. A disagreement in the reported version is "
    + "therefore traceable to the invocation, not the build.");
}

// --- 32: CI for Agent-Facing APIs -------------------------------------------
{
  const s = S();
  addDiagramSlide(s, "SHIP · CI FOR AGENT-FACING APIS", "Two gates, two different ways a promise breaks",
    "32-ci-for-agent-facing-apis",
    "A contract-drift gate and a schema-compatibility gate each name which specific check fired.");
  addNotes(s, "Gate (a), contract drift, is the conformance check already built for contract and manifest conformance: "
    + "the committed drifted fixtures prove it discriminates. Gate (b), schema compatibility, needs no conformance tool "
    + "at all, because Apicurio Registry already enforces it: a baseline JSON Schema registers with a COMPATIBILITY rule "
    + "set to BACKWARD, and a candidate next version is posted straight at the registry. No script in this harness "
    + "decides which outcome is correct; the registry's own rule engine does, confirmed against a live instance rather "
    + "than assumed from documentation, which is exactly what caught a dialect the checker could not parse.");
}
{
  const s = S();
  addCodeSlide(s, "SHIP · CI FOR AGENT-FACING APIS", "Let the registry decide, never a hand-rolled diff", "bash",
    [
      "curl -X POST \"$APICURIO/apis/registry/v3/groups/$GROUP/artifacts/$ARTIFACT/versions\" \\",
      "  -H 'Content-Type: application/json' \\",
      "  -d \"{\\\"content\\\":{\\\"content\\\":$schema_json,\\\"contentType\\\":\\\"application/json\\\"}}\"",
    ],
    "An added optional field returns 200; a new required field returns 400 naming RuleViolationException.");
  addNotes(s, "A version that only adds an optional field returns the new version and an HTTP 200: nothing a reader "
    + "built against the older schema ever depended on changed. A version that adds a new required field with no "
    + "default returns an HTTP 400 carrying RuleViolationException and a cause naming exactly what broke. Reading the "
    + "exception name, not just the status code, matters because a schema declaring a dialect the registry cannot parse "
    + "also returns a non-2xx response, a distinct UnprocessableSchemaException, before the compatibility algorithm "
    + "ever runs; treating any non-2xx as a correct rejection would let a broken checker pass as a working gate.");
}

// ===========================================================================
// SECTION 04: RUN IN PRODUCTION
// ===========================================================================
divider("04", "Run in production", "Observability, audit, identity, traffic guardrails, and the platform underneath",
  "This section covers what an agent-facing service needs once it is live: a durable cost trail behind every model "
  + "call, an audit record appended before an outbound call rather than after, an assurance floor under a destructive "
  + "write on top of role and scope, rate and shape guardrails at the ingest door, and the same reused artifact proven "
  + "again on a Kubernetes platform with Strimzi, CloudNativePG, and in-cluster Ollama. Every guardrail here is proven "
  + "to work independently of the others, not as one combined toggle.");

// --- 33: Observability for Agent Traffic ------------------------------------
{
  const s = S();
  addDiagramSlide(s, "RUN · OBSERVABILITY FOR AGENT TRAFFIC", "A trace answers what happened. A row answers what it cost.",
    "33-observability-for-agent-traffic",
    "A durable Postgres row is the primary witness; it survives a trace's own retention window.");
  addNotes(s, "Calling a langchain4j AiService method against a real model, with an OpenTelemetry extension and a "
    + "Micrometer registry present, makes the framework register its own chat-model listeners with no configuration "
    + "naming them: a GenAI span carrying gen_ai attributes for the call and each tool it invokes, and a token-usage "
    + "counter. TokenCostRecorder is a plain CDI bean implementing the same listener interface, discovered by the same "
    + "typesafe lookup; its response callback reads the token counts off the response and derives a cost at a fixed, "
    + "documented per-1000-token rate kept as a constant in code, so the number stays auditable rather than calling an "
    + "external pricing service.");
}
{
  const s = S();
  addContentTitle(s, "RUN · OBSERVABILITY FOR AGENT TRAFFIC", "The row, not the metric, is the thing to check");
  addBullets(s, [
    "A metrics backend can hold a previous value live across a restart, so a metric-presence check alone cannot tell a disabled recorder from a working one.",
    "Disabling the cost recorder still produces the framework's own trace and metric for a fresh call; only the durable row's count changes.",
    "Span names are confirmed from the resolved dependency, langchain4j.aiservices. and langchain4j.tools. prefixes, never assumed from a naming convention.",
    "A trace expires on a retention window. A cost or compliance question asked next quarter needs a row a trace store was never built to hold.",
  ], { fontSize: 17 });
  addNotes(s, "Reading the span names back from a running trace, rather than assuming a naming convention, showed the "
    + "AiService-level span carries a langchain4j.aiservices. prefix and the tool-call span a langchain4j.tools. prefix; "
    + "asserting the names the server actually emits is what makes the check that follows a check rather than a hope. "
    + "That confirmation explains why the cost row has to be the thing checked against a disabled recorder: disabling "
    + "it and making a fresh call still produces the framework's own GenAI span and token-usage sample, since those "
    + "come from the framework's own listeners, untouched by this service's own code.");
}

// --- 34: Auditing AI-Using Code ---------------------------------------------
{
  const s = S();
  addDiagramSlide(s, "RUN · AUDITING AI-USING CODE", "Append the record before the call, never after",
    "34-audit-before-call",
    "AuditedToolCaller appends to an append-only log before calling the remote tool, never after it returns.");
  addNotes(s, "A call a team's own agent code makes outward can be audited only if every path making such a call is "
    + "forced through the same recording step. The chokepoint here is a single CDI bean registered as the agent's only "
    + "local tool, rather than left to a framework's own remote-tool auto-execution. appendAuditRecord always runs "
    + "first, publishing one record, a correlation id, the target, a digest of the argument, and when it happened, to "
    + "an append-only Kafka log, folded into a live projection by inserting, never updating, keyed on the record's own "
    + "id, so a replay from the beginning always reproduces the live projection.");
}
{
  const s = S();
  addCodeSlide(s, "RUN · AUDITING AI-USING CODE", "One chokepoint every call site shares", "Java · Quarkus",
    [
      "@Tool(\"Get an order's composed status. Every call through this tool is audited \"",
      "        + \"on an append-only log before it goes out.\")",
      "public String lookupOrderStatus(String orderId) {",
      "    if (interceptorEnabled) {",
      "        appendAuditRecord(orderId);",
      "    }",
      "    return callRemote(orderId);",
      "}",
    ],
    "Auditing that a call happened and recording what it returned are different concerns.");
  addNotes(s, "Whichever path drives the call, the real model choosing to call this local tool, or a fixture calling it "
    + "directly with no model involved, both reach the identical chokepoint, so the audit record is appended the same "
    + "way regardless of which one produced it. The call's business effect, the order status and tracking number the "
    + "remote tool actually returned, is recorded separately, downstream of the chokepoint, which is what makes it "
    + "possible to disable the audit step alone and watch the call still succeed with nothing appended. A record "
    + "written only after a successful response misses every call that crashed, timed out, or threw before returning.");
}

// --- 35: Identity and Authorization Guardrails ------------------------------
{
  const s = S();
  addDiagramSlide(s, "RUN · IDENTITY AND AUTHZ GUARDRAILS", "A stronger credential, not a stronger caller, clears the gate",
    "35-token-exchange-step-up",
    "A low-assurance, read-scoped token is rejected by the destructive tool until exchanged for one carrying step-up assurance.");
  addNotes(s, "The read tool stays exactly as light as any role-gated read: a verified bearer token and a role. The "
    + "destructive tool stacks two checks on top of that same role, both read by hand from the injected JsonWebToken: a "
    + "write scope, and an assurance floor on the acr claim, Authentication Context Class Reference. A caller invoking a "
    + "destructive tool with the right role and even the right scope has only proven who is calling and what it is "
    + "permitted to do, never how strongly that credential was obtained, and a token minted once at the start of a "
    + "long-lived session can carry a write scope indefinitely without the caller ever proving anything more.");
}
{
  const s = S();
  addCodeSlide(s, "RUN · IDENTITY AND AUTHZ GUARDRAILS", "Trade a low-assurance token for one that proves more", "Java · Quarkus",
    [
      "private void requireStepUpAcr() {",
      "    if (!stepUpEnforced) return;",
      "    String acr = accessToken.getClaim(\"acr\");",
      "    if (!REQUIRED_STEP_UP_ACR.equals(acr)) {",
      "        throw new ForbiddenException(",
      "            \"token's acr claim does not meet the required step-up assurance level\");",
      "    }",
      "}",
    ],
    "The exchange happens entirely between the caller and the identity provider; this code only reads a verified claim.");
  addNotes(s, "The assurance claim is never minted by this application: a caller holding only a read-scoped, "
    + "low-assurance token performs an RFC 8693 token exchange against the identity provider, trading it for a new token "
    + "whose issuing client stamps both the write scope and the step-up acr value onto it. A token missing the write "
    + "scope is rejected regardless of its assurance level, and a token missing the assurance level is rejected "
    + "regardless of its scope; only a token carrying both ever reaches the mutation. An acr claim nobody verifies is a "
    + "guard that looks present and gates nothing.");
}

// --- 36: Traffic and Resource Guardrails ------------------------------------
{
  const s = S();
  addDiagramSlide(s, "RUN · TRAFFIC AND RESOURCE GUARDRAILS", "Reject at the door, not partway through the business logic",
    "36-ingest-guardrails",
    "Either guardrail, the rate limit or the schema gate, can reject a call before the other ever runs.");
  addNotes(s, "An endpoint that accepts every call at whatever rate it arrives, and parses whatever body it receives "
    + "before checking its shape, has no floor under either axis of abuse: volume and validity. The rate limit is one "
    + "@RateLimit annotation from quarkus-smallrye-fault-tolerance, a CDI interceptor that runs before the method body "
    + "ever executes, keyed by bean class and method so every call against the endpoint counts against the same shared "
    + "budget. A call that clears the rate limit is routed through a Camel route that checks the raw body against a "
    + "committed draft-07 JSON Schema with the Camel JSON Schema Validator component before any domain object is ever constructed.");
}
{
  const s = S();
  addCodeSlide(s, "RUN · TRAFFIC AND RESOURCE GUARDRAILS", "Two guardrails, two independent toggles", "Java · Quarkus",
    [
      "@POST @Path(\"/orders\")",
      "@RateLimit(value = 5, window = 30, windowUnit = ChronoUnit.SECONDS)",
      "public Response ingestOrder(String rawBody) { ... }",
      "",
      "@ServerExceptionMapper",
      "public RestResponse<RateLimitProblem> mapRateLimited(RateLimitException e) {",
      "    rejectionRecorder.record(\"rate_limit\");",
      "    return RestResponse.ResponseBuilder.create(RestResponse.Status.TOO_MANY_REQUESTS, ...)",
      "            .type(\"application/problem+json\").build();",
      "}",
    ],
    "A rejection for either reason leaves a durable row and a tagged counter, not only a status code seen once.");
  addNotes(s, "A call over the limit throws RateLimitException, which by default surfaces as a bare 500; a "
    + "ServerExceptionMapper maps it to a 429 instead, and a payload that fails the schema throws a "
    + "JsonValidationException mapped to a 400 naming each violation. Disabling the rate limit alone, and the schema "
    + "gate alone, each confirms the other guardrail still rejects on its own; a single combined toggle for both would "
    + "hide which guardrail actually caught a given rejection, and make it impossible to prove either works "
    + "independently. Checking shape after acting on it would mean a malformed call already triggered deserialization "
    + "cost and any constructor side effect before the service decides to reject it.");
}

// --- 37: Running on Kubernetes with Strimzi ---------------------------------
{
  const s = S();
  addDiagramSlide(s, "RUN · KUBERNETES WITH STRIMZI", "The same artifact, proven again on a cluster substrate",
    "37-deploying-onto-the-platform",
    "A reused service's Deployment reaches Strimzi Kafka, CloudNativePG Postgres, and Ollama by in-cluster DNS.");
  addNotes(s, "Every pattern proven so far in this catalog ran on one Docker Compose stack; production rarely offers "
    + "that shape. One minikube profile hosts the whole platform, brought up in tiers so a Kafka cluster resource never "
    + "applies against a Strimzi operator that is not yet ready. A chapter application's image builds on the host and "
    + "loads directly into the profile's containerd runtime with minikube image load, never through a registry, and its "
    + "Deployment reads a shared ConfigMap for Kafka, Postgres, Ollama, and OTLP endpoints, the same four values a "
    + "compose file would otherwise set by container name; nothing about the service's code differs between the two targets.");
}
{
  const s = S();
  addContentTitle(s, "RUN · KUBERNETES WITH STRIMZI", "Every host-facing endpoint is a fixed NodePort");
  addStatusTable(s, [
    { code: "9092", name: "Kafka (external)", purpose: "Strimzi, KRaft only, no ZooKeeper" },
    { code: "5432", name: "PostgreSQL", purpose: "CloudNativePG operand image" },
    { code: "3000 / 3200 / 19090 / 3100", name: "Grafana / Tempo / Prometheus / Loki", purpose: "one grafana/otel-lgtm image" },
    { code: "8180 / 8081 / 11434", name: "Keycloak / Apicurio / Ollama", purpose: "identity, registry, model runtime" },
  ], { colW: [2.60, 4.30, 5.19] });
  addNotes(s, "There is no tunnel and no proxy at any layer; every host-facing endpoint is a NodePort fixed when the "
    + "profile is created. Asserting a deployed service's observable effect goes through a platform NodePort and "
    + "kubectl exec, never a forward. A Prometheus metric's last-exported sample can stay live well past a rollout, so "
    + "a metric-name check alone would pass after the behaviour it claims to prove has stopped; the durable row is the "
    + "real negative witness. Flipping a Deployment's consumer flag, not its source, and watching the missed event "
    + "recover once it is restored is the same discipline proven on compose, now proven on the cluster.");
}

// ===========================================================================
// SECTION 05: THE FOCUSED MULTI-DOMAIN DATA MESH
// ===========================================================================
divider("05", "The focused multi-domain data mesh", "Three independently owned products, one governance plane, one end-to-end scenario",
  "The final project assembles three already-built agent-facing services, orders, payments, and shipping, into one "
  + "mesh and asks one question of the result: when an agent reads from one product, writes through another, and the "
  + "second product's effect depends on an event the first one caused, does the record each product keeps independently "
  + "still add up to one consistent story. Domain ownership, a self-serve platform, and a live governance gate only "
  + "matter if they hold under that question, not just in how the cluster is described.");

// --- 38a: Dehghani's four principles mapped to concrete artifacts -----------
{
  const s = S();
  addContentTitle(s, "FINAL PROJECT · THE DATA MESH", "Four principles, four concrete artifacts");
  addTwoColBullets(s, [
    { text: "Domain ownership: each product owns one database in the shared cluster; a query against another product's schema name fails outright." },
    { text: "Data as a product: three independently callable MCP output ports, each its own handshake and NodePort, not one service wearing three names." },
  ], [
    { text: "Self-serve platform: one aggregate manifest deploys all five Deployments and Services together, the same tiered platform every earlier chapter already proved." },
    { text: "Federated computational governance: one compatibility rule, registered once, applied live against any of the three products' contracts." },
  ], { fontSize: 16 });
  addNotes(s, "Zhamak Dehghani's four data mesh principles, domain ownership, data as a product, a self-serve data "
    + "platform, and federated computational governance, are not restated as prose here; each one is a boundary a "
    + "query, a handshake, a manifest, or a registry call actually hits. Orders stays read-only against a database it "
    + "fully owns; payments splits into an agent-facing facade with no database of its own and an internal service "
    + "owning the payments database alone; shipping splits into a read port and an event-woken writer, each owning its "
    + "own database, so neither can reach the other's store and their migration histories never collide.");
}

// --- 38b: the governance plane -----------------------------------------------
{
  const s = S();
  addContentTitle(s, "FINAL PROJECT · THE GOVERNANCE PLANE", "One plane, three products, no per-product reinvention");
  addBullets(s, [
    "A live Apicurio BACKWARD-compatibility gate: an added optional field returns 200, a new required field is rejected naming RuleViolationException, confirmed against the running registry.",
    "A named caller scope every write tool requires, stated once rather than invented three separate times.",
    "A replayable, append-only audit obligation: insert, never update, so a replay from the start reproduces the live record.",
    "The plane sits across all three products rather than being built into any one of them; a production deployment of the same mesh adds one rule per product instead of inventing three.",
  ], { fontSize: 17 });
  addNotes(s, "A governance product, a fourth deployable just to hold the compatibility rule and the audit obligation, "
    + "would turn something cross-cutting into one more thing to deploy, version, and keep in sync with the three it is "
    + "supposed to govern; the mesh instead names these rules once, against the shared registry, and applies them live. "
    + "Skipping the compatibility gate because nothing has broken yet protects nothing: every consumer built against the "
    + "old shape has already broken by the time anyone notices a change shipped without it.");
}

// --- 38c: exercise-it-yourself payoff -----------------------------------------
{
  const s = S();
  addContentTitle(s, "FINAL PROJECT · DRIVE IT YOURSELF", "The reader drives the mesh and watches the effect, not a pass count");
  addBullets(s, [
    "Call each product's own output port directly over its own NodePort: an MCP handshake and a tool call, three times, against three different ports.",
    "Take a payment through the facade, produce the resulting event by hand, then poll the shipping database until the woken write lands, carrier chosen live by the model.",
    "Scale the shipping writer to zero and watch a paid order sit unshipped; scale it back and watch the missed event recover once it resumes.",
    "Nothing here is read from a dashboard. Every claim is a query or a call the reader runs by hand against the live cluster.",
  ], { fontSize: 17 });
  addNotes(s, "Running the mesh with KEEP=1 leaves it and its databases up once the automated checks finish, so every "
    + "step after that drives the same live mesh by hand, over the same NodePorts and the same kubectl exec, instead of "
    + "only reading a pass count. The agent-driven flow is the centerpiece: nothing calls the shipping product directly, "
    + "the only link between a payment and the resulting write is the Kafka topic, and that choreography across "
    + "independently owned products is exactly what the mesh exists to prove. A stood-in event producer, used here "
    + "because no reused service publishes the payment-processed event on its own, is named as a gap rather than hidden.");
}

// ---- Closing ---------------------------------------------------------------
{
  const s = S();
  addContentTitle(s, "CLOSING", "The arc from a tool call to a governed mesh");
  addBullets(s, [
    "Consuming an agent-facing API well means driving only what tools/list advertises, and keeping a durable record traceable to the captured call, never to a model's retelling of it.",
    "Testing it well means asserting structured, captured signals, a transcript, a report, a durable row, and proving every check bites with a committed negative witness.",
    "Shipping and running it well means one version across every artifact, two CI gates that each name their own failure, and guardrails that work independently of each other.",
    "The final project is the same disciplines, composed: domain ownership, product thinking, a self-serve platform, and governance, holding under one real agent-driven scenario.",
  ], { fontSize: 17 });
  addNotes(s, "Every pattern in this deck traces back to the same habit: read the structured, captured signal a system "
    + "actually produced, never the natural-language account of it, whether that account comes from a model, a "
    + "transcript, or an exit code. The final project is not a new idea, it is the proof that the disciplines from the "
    + "first two decks in this series, and from every chapter in this one, compose across independently owned products "
    + "without any of them needing to be rebuilt. Close by pointing to the book's own chapters for the full build and "
    + "verify.sh output behind every slide shown today.");
}

// ---- References --------------------------------------------------------------
{
  const s = S();
  addContentTitle(s, "REFERENCES", "Sources behind this deck");
  addTwoColBullets(s, [
    { text: "Hohpe and Woolf, Enterprise Integration Patterns: the vocabulary behind triggers, consumers, idempotent writes, and audit logs throughout this deck." },
    { text: "Dehghani, Data Mesh: Delivering Data-Driven Value at Scale: the four principles behind the final project's domain ownership, product thinking, platform, and governance." },
    { text: "Model Context Protocol specification: the handshake, tool, resource, and prompt lifecycle every consume-side chapter drives." },
    { text: "Anthropic, Claude Code documentation (MCP and Skills), and Writing Effective Tools for AI Agents." },
    { text: "RFC 8693, OAuth 2.0 Token Exchange, and RFC 9470, OAuth 2.0 Step-Up Authentication Challenge Protocol." },
    { text: "OpenID Connect Core 1.0, and Keycloak documentation." },
  ], [
    { text: "RFC 9457, Problem Details for HTTP APIs: the error shape behind the guardrail and scripted-consumption checks." },
    { text: "OpenTelemetry Semantic Conventions for Generative AI, and Grafana LGTM (Loki, Grafana, Tempo, Mimir): the signals behind observability and cost." },
    { text: "Apicurio Registry documentation: the live compatibility gate behind CI and the data mesh's governance plane." },
    { text: "Strimzi, CloudNativePG, Istio, Kiali, and Kubernetes documentation: the platform the final project and the Kubernetes chapter run on." },
    { text: "Schemathesis and Newman documentation: the pinned, maintained runners behind contract conformance and scripted consumption." },
    { text: "JSON Schema and OpenAPI specifications, the Postman collection format, and llms.txt." },
  ], { fontSize: 13 });
  addNotes(s, "Every source named here is credited by author or project, never copied from; the pattern chapters this "
    + "deck is mined from each carry their own full reference list. Dehghani's book is the direct source for the final "
    + "project's four organizing principles. The two RFCs anchor the identity chapter's step-up assurance pattern, "
    + "confirmed against a real Keycloak token exchange rather than assumed from the spec text. Point the audience to "
    + "the book's own References sections, chapter by chapter, for the complete list behind any single pattern shown today.");
}

pres.writeFile({ fileName: OUT })
  .then(p => console.log("WROTE", p))
  .catch(e => { console.error(e); process.exit(1); });
