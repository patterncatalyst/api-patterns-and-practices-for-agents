// deck.js: "APIs for Agents and LLMs: the 101"
// Deck 1 of 3 companion decks for API Patterns & Practices: APIs for Agents and LLMs.
//
// Build:  cd presentation/apis-for-agents-101 && node deck.js
// Paths:  deck-helpers.js reads DECK_ASSETS (./assets) and DECK_PNG (./png).

"use strict";

const H = require("./deck-helpers.js");
const {
  COLOR, FONT, W, ASSETS,
  newDeck, addFooter, addContentTitle, addBullets,
  addStatusTable, addCaption, addCodeSlide, addDiagramSlide, addSectionDivider, addNotes,
} = H;

const OUT = "./apis-for-agents-101-r17.0.pptx";
const REV = "r17.0";

const pres = newDeck();

// Public-repo scrub: newDeck() hardcodes metadata for a different, earlier deck
// in this series. Overwrite it so the shipped file never names the wrong title
// or author.
pres.title = "APIs for Agents and LLMs: the 101";
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

// ================================================================
// Cover
// ================================================================
{
  const s = pres.addSlide();
  pageNum += 1;
  s.background = { color: COLOR.white };
  try { s.addImage({ path: `${ASSETS}/cover-panel.png`, x: 0, y: 0, w: W, h: 7.5 }); } catch (e) {}
  s.addText("API PATTERNS & PRACTICES: AGENTS AND LLMS", { x: 6.00, y: 1.98, w: 6.90, h: 0.34,
    fontFace: FONT.title, fontSize: 14, bold: true, color: COLOR.red, charSpacing: 6, align: "left", valign: "middle" });
  s.addText([
    { text: "APIs for Agents", options: { breakLine: true } },
    { text: "and LLMs: the 101" },
  ], {
    x: 5.95, y: 2.42, w: 6.95, h: 2.00, fontFace: FONT.title, fontSize: 46, bold: true, color: COLOR.ink, align: "left", valign: "top" });
  s.addText("Why agent-facing APIs are different, and MCP as one facade over REST, GraphQL and gRPC.", {
    x: 6.00, y: 4.55, w: 6.70, h: 1.10,
    fontFace: FONT.body, fontSize: 17, italic: true, color: COLOR.caption, align: "left", valign: "top" });
  s.addText(REV, { x: 11.85, y: 5.85, w: 0.95, h: 0.30, fontFace: FONT.mono, fontSize: 11, color: COLOR.caption, align: "right", valign: "middle" });
  try { s.addImage({ path: `${ASSETS}/logo-candidate-2.png`, x: 11.10, y: 6.80, w: 1.55, h: 0.37 }); } catch (e) {}
  addNotes(s,
    "This talk is the 101 for a pattern catalog on building APIs that AI agents and large " +
    "language models consume, not APIs built for a browser or a human developer. The core claim " +
    "is simple to state and easy to underestimate: an agent reads a tool's name, description and " +
    "schema fresh every time it decides whether to call it, with no persistent memory between " +
    "calls, so the contract itself is the only instruction the agent ever gets. We will look at " +
    "why that makes agent-facing API design a different engineering job, how the catalog organizes " +
    "that job, and how the Model Context Protocol works as one facade sitting in front of REST, " +
    "GraphQL and gRPC services underneath it."
  );
}

// ================================================================
// Section 01: WHY
// ================================================================
divider("01", "Why agent-facing APIs are different", "Same task, two contracts, measured",
  "This opens with the foundational claim of the whole catalog: designing an API for an agent " +
  "or a large language model is a systematically different engineering job from designing one " +
  "for a human or a browser, not a smaller version of the same job. An agent reads a tool's name, " +
  "description and argument schema at the exact moment it decides whether to call it, inside a " +
  "window that also holds the conversation and every earlier tool result, and it cannot ask a " +
  "clarifying question before it acts. The next few slides walk through why that matters and then " +
  "show a recorded comparison: the same task, run against the same local model, once through a " +
  "bad contract and once through a well-shaped one."
);

{
  const s = S();
  addDiagramSlide(s, "WHY · THE CALLER IS DIFFERENT", "Agents are not browsers", "01-contract-contrast",
    "Same task, same model, two contracts: one completes the refund, one does not.");
  addNotes(s,
    "A human developer reads a guide once, forms a mental model, and calls an endpoint from " +
    "memory for months. An agent has none of that: every call it makes is decided fresh, from " +
    "whatever the tool definition says at that moment, because it has no persistent memory of an " +
    "API across calls unless something puts it back in context. A vague tool name or an " +
    "ambiguous parameter does not slow a human down for long, but it produces a wrong call from " +
    "an agent immediately, because the definition is the only instruction it ever gets. This " +
    "figure is the recorded contrast we will look at in detail in a moment: the same task given " +
    "to the same model, once against a granular, CRUD-shaped contract and once against a single " +
    "intent-named tool."
  );
}

{
  const s = S();
  addContentTitle(s, "WHY · THE COST MODEL", "Five pressures no human-facing API feels");
  addBullets(s, [
    "An agent reads a tool's name, description and schema fresh at every call: it has no persistent memory of an API between calls, and cannot ask a clarifying question first.",
    "Every tool definition registered for a session, and every field a response returns, costs tokens inside one bounded context window, before the agent has done anything useful.",
    "A crowded or ambiguous tool list degrades tool-selection accuracy directly: the agent picks the wrong one more often, or misses the right one entirely.",
    "A bare status code tells an agent that something failed and nothing about what to try next, so the error text is the only recovery signal it ever gets.",
    "Nothing in a loop stops for a spinner or rereads a confirmation dialog: an unguarded retry on a write can double-submit the same operation.",
  ], { fontSize: 16 });
  addNotes(s,
    "These five pressures are named once in the catalog and then invoked by name on every later " +
    "page, so it is worth being precise about them here. Context budget and token economy both " +
    "come from the same bounded window: every tool definition and every returned field is tokens " +
    "spent before the agent has accomplished anything, so an internal identifier nobody needs " +
    "costs exactly as much as a field the agent actually uses. Error legibility matters because " +
    "an agent's only recovery signal is the text an error actually contains, never a developer " +
    "reading a stack trace. And autonomy matters because a browser-driven workflow has a person " +
    "who notices a double click; an agent calling an API in a loop does not, by default, which is " +
    "why idempotency keys and asynchronous human-in-the-loop gates exist as patterns in their own " +
    "right later in the catalog."
  );
}

{
  const s = S();
  addContentTitle(s, "WHY · MEASURED, NOT ASSERTED", "Same task, same model, two contracts");
  addStatusTable(s, [
    { code: "Tool calls", name: "1", purpose: "5" },
    { code: "Argument errors", name: "0", purpose: "2" },
    { code: "Tokens, input plus output", name: "759", purpose: "3,185" },
    { code: "Refund actually completed", name: "Yes", purpose: "No" },
  ], { colW: [5.60, 2.80, 3.69] });
  addCaption(s, "Columns, left to right: the metric, the intent-named tool, the three granular CRUD-shaped tools.");
  addNotes(s,
    "This is a recorded run, not a hypothetical: the task \"cancel this order and refund the " +
    "customer\" was given to the same local model through the same AiService interface, once " +
    "against a single intent tool that does the whole job, and once against three granular tools " +
    "an agent has to sequence itself. The good contract's one call succeeded cleanly on the first " +
    "attempt. On the bad contract, the agent guessed a refund amount, got an opaque error, called " +
    "a second tool to find the real amount, called the refund tool again without required data, " +
    "got a second opaque error, and gave up: the order ended up cancelled and the customer was " +
    "never refunded. Every failure on the bad contract was a bare opaque code, with nothing " +
    "explaining what was wrong or what to try instead, which is exactly the error-legibility " +
    "pressure made concrete."
  );
}

{
  const s = S();
  addCodeSlide(s, "WHY · THE NEGATIVE WITNESS", "What a bad contract looks like", "contract shapes",
    [
      "// bad: three granular, CRUD-shaped tools the agent must",
      "// sequence correctly itself, in the right order, with",
      "// the right data carried between them",
      "getOrder(orderId)",
      "updateOrderStatus(orderId, status)",
      "createRefund(orderId, amount)",
      "",
      "// good: one tool named for the operation itself",
      "cancelAndRefund(orderId, reason)",
    ],
    "One call that succeeds, or five that do not: the composition work either happens on the server once, or on the agent every time.");
  addNotes(s,
    "The granular contract is not a strawman built to lose; it is what a database-shaped, " +
    "CRUD-first API design produces by default. Each of those three calls is individually " +
    "reasonable, and a human developer sequences them correctly from memory without even " +
    "thinking about it. An agent has to get the sequencing right from the tool descriptions " +
    "alone, every single time, with no memory of having done it before. A single intent-named " +
    "tool moves that composition work onto the server, which already has every piece of data it " +
    "needs, instead of leaving it to be assembled correctly inside a model's own reasoning on " +
    "every call."
  );
}

// ================================================================
// Section 02: CATALOG
// ================================================================
divider("02", "The organizing idea", "One form, seven forces, five lifecycle stages",
  "Before getting into any one pattern, it helps to see the shape of the whole catalog. Every " +
  "entry answers the same questions in the same order: a name, a problem, a one-sentence " +
  "solution, how Quarkus and Camel model it, a runnable build-run-observe step that always ends " +
  "in a negative witness, and when not to use it. That consistency is what lets two entries from " +
  "completely different parts of the catalog still be compared on the same terms. This section " +
  "covers the lifecycle taxonomy that organizes every entry, the seven forces each one declares, " +
  "and the pattern form itself."
);

{
  const s = S();
  addDiagramSlide(s, "CATALOG · THE TAXONOMY", "Organized by lifecycle, not by technology", "02-taxonomy",
    "Lifecycle first, synchronous versus asynchronous second inside building, the seven forces as a third dimension on every entry.");
  addNotes(s,
    "Entries group first by where an API sits in its own lifecycle: building the surface, " +
    "consuming it from an agent harness, testing its contract and behavior, shipping it, and " +
    "running it under observation and guardrails in production. Lifecycle is the primary axis " +
    "because the concerns at each stage are different problems, not just different " +
    "depths of the same one. Inside the building stage, a second axis separates synchronous " +
    "patterns, a REST or GraphQL endpoint reshaped into an MCP tool an agent calls directly, from " +
    "asynchronous ones, a Kafka event that wakes an agent up on its own schedule instead of " +
    "waiting to be polled. The seven forces sit as a third dimension across both axes, and " +
    "naming them explicitly is what lets two entries from different stages still compare on the " +
    "same terms."
  );
}

{
  const s = S();
  addContentTitle(s, "CATALOG · THE FORCES", "Seven forces every pattern trades against");
  addBullets(s, [
    "Context budget: the agent holds the whole API surface and every returned result inside one bounded window, all at once.",
    "Token economy: every tool definition and every returned field costs tokens in that window, on every single call.",
    "Tool-selection accuracy: the agent picks a tool from its name and description alone, and a crowded surface degrades that pick.",
    "Error legibility: the agent recovers only from what an error's text actually says, never from a developer reading a stack trace.",
    "Reasoning and execution boundary: a model reasons about what to do, the application decides whether that action actually happens.",
    "Trust boundary: private data, untrusted content and a path for that data to leave combine into the lethal trifecta.",
    "Autonomy level: a synchronous tool call, an asynchronous wakeup, or a step that needs a human in the loop.",
  ], { fontSize: 14.5 });
  addNotes(s,
    "Naming these here means no later pattern page has to re-derive them: every entry states " +
    "which of these seven forces it resolves and which it knowingly trades away. A tool surface " +
    "built around these forces is also a smaller trust boundary on its own terms: fewer, " +
    "well-scoped tools give private data, untrusted content and an exit path less room to combine " +
    "into an exfiltration risk. The reasoning-and-execution boundary is worth sitting with for a " +
    "moment, because it recurs constantly later: a model's decision to act and the system's " +
    "decision to let that action happen are never allowed to collapse into the same step."
  );
}

{
  const s = S();
  addDiagramSlide(s, "CATALOG · THE PATTERN FORM", "Every entry ends in a negative witness", "02-pattern-form",
    "Name, Problem, Solution, how Quarkus and Camel model it, build-run-observe, when not to use it, References.");
  addNotes(s,
    "A pattern page opens with a name, the identifier used everywhere else that entry is cited, " +
    "states the problem that makes the default human-shaped design fail for an agent caller, and " +
    "states the solution as one bold, concrete sentence. Build, run, observe gives the commands, " +
    "names what to watch for, and ends with a pass count from a runnable example whose checks " +
    "always include a negative witness, a check that fails once the pattern's own guard is " +
    "removed. That last part matters more than it sounds: a check that always passes, even after " +
    "the behavior it is supposed to prove has been deleted, has not actually proven anything. When " +
    "not to use it states the trade-off directly, because every pattern here costs something."
  );
}

// ================================================================
// Section 03: FACADE
// ================================================================
divider("03", "MCP as a unified facade", "One contract, three protocols behind it",
  "MCP is not a replacement for REST, GraphQL or gRPC underneath it. It is a facade an agent " +
  "calls through, and what sits behind that facade can be any one of those protocols, or more " +
  "than one at a time. This section walks through one CDI bean serving three transports without " +
  "drift, a composed tool response replacing three chatty calls, a facade reshaping a completely " +
  "different wire protocol at the boundary, and the discovery mechanics that keep a wide " +
  "operation catalog legible to a model."
);

{
  const s = S();
  addCodeSlide(s, "FACADE · ONE BEAN, THREE FACES", "Hold the logic once, serve it three ways", "java · CDI",
    [
      "@ApplicationScoped",
      "class OrderOperations {",
      "    OrderSummary orderSummary(String orderId) { ... }",
      "}",
      "",
      "// REST, GraphQL and the MCP tool all delegate to",
      "// ops.orderSummary(id) and return that same object,",
      "// unchanged, on every one of the three faces.",
    ],
    "Diverging one face from the shared bean is the only way to make the three transports disagree.");
  addNotes(s,
    "A REST endpoint, a GraphQL query and an MCP tool that each reimplement the same read " +
    "independently are three places the same business rule has to be kept correct, and three " +
    "places it can quietly stop being the same rule. The fix is one CDI bean holding the business " +
    "logic exactly once, with every transport delegating to it unchanged, so the same input always " +
    "returns the same result regardless of which face a caller used. The negative witness here is " +
    "direct: changing the GraphQL resolver to stop delegating and return a different value for one " +
    "field still lets that face run and still return a result for the right order id, so a check " +
    "on that face alone would still pass, but a check comparing all three faces against each other " +
    "fails. One operational note worth carrying forward: the MCP stdio transport reads standard " +
    "input, so a container started detached has no stdin to read and shuts down within seconds " +
    "unless stdio is disabled for that runtime profile."
  );
}

{
  const s = S();
  addDiagramSlide(s, "FACADE · THE TRIAD", "Compose for the question, not the endpoint", "13-mcp-triad",
    "A naive one-for-one wrap leaves an agent to assemble three answers itself; the tool, resource and prompt triad composes the read once.");
  addNotes(s,
    "An agent asking where an order stands, against a literally wrapped API, makes three separate " +
    "tool calls, one per resource, and reassembles the answer in its own context. Packaging an " +
    "existing API as MCP tools is not a transport swap: the same chattiness a REST client " +
    "tolerated becomes three tool-selection decisions and three round trips an agent has to get " +
    "right in sequence. The fix composes server-side instead: a tool's shape answers the question " +
    "a caller actually asks, a resource carries standing reference material a tool description " +
    "should not have to repeat on every call, and a prompt packages a proven way to ask for the " +
    "answer. Reducing a composed response to a passthrough of one narrow read, leaving the other " +
    "fields silently empty, breaks the composition guarantee without breaking the call itself, " +
    "which is the pattern's own negative witness."
  );
}

{
  const s = S();
  addDiagramSlide(s, "FACADE · RESHAPE AT THE BOUNDARY", "A gRPC service behind the same facade", "15-reshape-grpc",
    "Reshape a decimal amount to integer cents and back, and turn a protocol-level status into an instructive error.");
  addNotes(s,
    "An agent calling a tool should never need to know what protocol sits behind it. A gRPC " +
    "service speaks proto3, which has no decimal type, so a forty-nine dollar and ninety-nine " +
    "cent payment exists on that side of the wire as the integer four thousand nine hundred and " +
    "ninety-nine. A facade that passes that integer through unchanged, or lets a raw gRPC status " +
    "reach the caller, has not packaged the service as an MCP tool, it has relocated the gRPC " +
    "service's own wire format one hop closer to the agent. The payment service and the facade " +
    "here are two entirely separate Quarkus applications, two images, two compose services: the " +
    "payment service speaks gRPC only and has no MCP, REST or GraphQL endpoint of its own, and its " +
    "gRPC port is never published outside the compose network, because that protocol is an " +
    "implementation detail an agent never calls directly."
  );
}

{
  const s = S();
  addCodeSlide(s, "FACADE · RESHAPE, NOT WRAP", "Translate the value, not just the transport", "java · boundary",
    [
      "long toCents(String decimalAmount) {",
      "    return new BigDecimal(decimalAmount)",
      "        .setScale(2, HALF_UP)",
      "        .movePointRight(2).longValueExact();",
      "}",
      "",
      "String toDecimal(long cents) {",
      "    return BigDecimal.valueOf(cents, 2).toPlainString();",
      "}",
    ],
    "Skip this reshape and a $49.99 payment becomes $49: the call still succeeds, the number is wrong.");
  addNotes(s,
    "This is the one place the decimal boundary gets crossed, in both directions: a decimal " +
    "string reshaped to integer cents on the way into the gRPC call, and integer cents reshaped " +
    "back to a decimal string on the way out. A StatusRuntimeException from the gRPC call gets " +
    "turned into a message an agent can act on before it ever reaches the tool result, a not-found " +
    "status becomes a plain sentence saying no payment was found, a failed-precondition status " +
    "becomes a sentence saying that payment cannot be refunded. Skipping the reshape and wiring " +
    "the cents straight off the unscaled decimal string is the pattern's own negative witness: the " +
    "round trip still completes, the call still succeeds, but the number an agent reads back is " +
    "wrong, which isolates exactly the reshape this pattern exists to perform."
  );
}

{
  const s = S();
  addContentTitle(s, "FACADE · KEEPING THE SURFACE LEGIBLE", "Small live toolsets, a shared catalog for the rest");
  addBullets(s, [
    "Two domain-scoped MCP servers each advertise only their own small set of live tools, namespaced so a name collision fails the build instead of surprising a caller at runtime.",
    "A shared catalog resource and a search tool let a caller discover and read about the rest of a twenty-operation catalog without any of it becoming a live tool call.",
    "Sixteen of twenty catalog entries are never a live tool: a caller searches, reads the detail, and either calls the named live tool or stops there, having read what it needed.",
  ], { fontSize: 17 });
  addNotes(s,
    "A model picks a tool from whatever the current tool list advertises, and picking accuracy " +
    "degrades as that list grows, so an order-to-delivery domain with twenty plausible operations " +
    "does not need all twenty live on one server to be usable. The fix splits the operation set " +
    "across named, domain-scoped servers, each with its own root path and its own short tool " +
    "list, while a catalog resource and a search tool stay identical and reachable from either " +
    "connection, so either one sees the full operation set even though its own live tools stay " +
    "domain-local. MCP's tool namespace is flat, so a duplicate tool name is not a runtime " +
    "surprise waiting to happen: the framework rejects it during build-time augmentation, the " +
    "same place a duplicate CDI bean or a malformed route would be caught."
  );
}

// ================================================================
// Section 04: STACK
// ================================================================
divider("04", "The stack", "Quarkus, Camel, Kafka, one identity provider",
  "Everything in this catalog runs on one application stack and one separate observability " +
  "stack, both plain Docker Compose, both started and stopped independently of each other. " +
  "Quarkus and Apache Camel build the services, Kafka carries the events between them, and " +
  "Keycloak is the single identity provider for the entire catalog. This section also names the " +
  "exact pins that matter if you want to reproduce any of this yourself, and the two different " +
  "harnesses that actually call the tools once they exist."
);

{
  const s = S();
  addDiagramSlide(s, "STACK · TWO INDEPENDENT STACKS", "The application stack and the observability stack", "00-the-two-stacks",
    "Two Compose stacks, started and stopped independently: the application stack carries the request, the observability stack receives what it emits.");
  addNotes(s,
    "The application stack carries the actual request, an HTTP call, an event on Kafka, or a " +
    "tool call from an agent, and it declares five services on one Docker network: Kafka in KRaft " +
    "mode, PostgreSQL on the CloudNativePG operand image, Apicurio Registry for schemas, Keycloak " +
    "as the identity provider, and Ollama as the local model runtime. The observability stack " +
    "receives whatever OTLP signals that request produces, as one grafana otel-lgtm container " +
    "behind a single OTLP endpoint: Loki, Grafana, Tempo and Prometheus. Neither stack needs the " +
    "other running to work: the application stack passes its own checks on its own, and the " +
    "observability stack only adds a place to look afterward."
  );
}

{
  const s = S();
  addContentTitle(s, "STACK · TWO WAYS IN", "Two harnesses call the same tool contract");
  addBullets(s, [
    "Claude Code is the reference harness: an interactive agent that registers an MCP server, discovers its tools, resources and prompts, and decides for itself which to call.",
    "A quarkus-langchain4j AiService is the programmatic alternative: a Java interface that calls the same tools against the pinned local model, with no human watching.",
    "Neither harness cares whether the service behind a tool is REST, GraphQL or a Kafka consumer: the tool contract, a name, a description and a typed schema, is the only thing either one sees.",
  ], { fontSize: 17 });
  addNotes(s,
    "Claude Code is how a reader actually drives an example by hand, and it is the harness every " +
    "cross-stack callout in the catalog targets first. The programmatic AiService consumer backed " +
    "by quarkus-langchain4j-ollama is a smoke test by design, not a full programmatic agent: it " +
    "proves one tool call round-tripped and observed in a log line, not a model's prose, and " +
    "driving a full programmatic agent with retries, memory and multi-step plans against a real " +
    "tool surface is covered in its own right elsewhere in the catalog. Both harnesses, though, " +
    "arrive at the exact same tool contract, which is the entire point of building the contract " +
    "once and facading everything behind it."
  );
}

{
  const s = S();
  addContentTitle(s, "STACK · THE PINS THAT MATTER", "Exact pins, chosen once");
  addStatusTable(s, [
    { code: "MCP protocol revision", name: "2025-11-25", purpose: "Negotiated on every initialize handshake" },
    { code: "quarkus-mcp-server", name: "2.0.4", purpose: "Streamable HTTP and stdio transports" },
    { code: "quarkus-langchain4j", name: "1.13.4 (langchain4j 1.19.3)", purpose: "The AiService consuming side" },
    { code: "Ollama model", name: "qwen3:4b-instruct-2507-q4_K_M", purpose: "Apache-2.0, tool-calling, sized for a laptop CPU" },
  ], { colW: [3.60, 3.60, 4.89] });
  addNotes(s,
    "Every pin in this catalog is an exact release, chosen once and moved only for a security " +
    "fix, a real incompatibility or a feature an example needs, never a floating or latest tag. " +
    "The MCP protocol revision shown here is simply what this catalog's clients request on the " +
    "initialize handshake; the same server jar also advertises several earlier revisions for a " +
    "client that asks for one of those instead. quarkus-langchain4j is managed by its own bill of " +
    "materials, which in turn pins the underlying langchain4j library version, so the whole " +
    "consuming side moves together. The model itself is chosen for being small enough to run on a " +
    "laptop CPU while still being capable of tool calling, which is the only capability every " +
    "example in this catalog actually needs from it."
  );
}

// ================================================================
// Section 05: AUDIT
// ================================================================
divider("05", "Audit both directions", "What agents call us for, and what we call out for",
  "Observability here runs in both directions, and it is worth treating them as two separate " +
  "concerns rather than one. One direction is what an agent calls on us for: a model call that " +
  "leaves a trace, a metric and a durable cost row behind it. The other direction is what our " +
  "own code calls out for: every outbound call our own agent-driven code makes gets audited " +
  "before it leaves, never after. Both of these live on the same Grafana LGTM stack."
);

{
  const s = S();
  addContentTitle(s, "AUDIT · INBOUND", "A model call leaves a trail you can cost");
  addBullets(s, [
    "A framework that already understands the call's shape emits its own GenAI span and a token-usage metric on its own: no hand-rolled span to drift from the library's own attribute names.",
    "A trace expires on a retention window, while a cost or compliance question asked next quarter needs a row that survives it, not a trace.",
    "A plain CDI bean implementing the chat model's listener interface derives a per-call cost from the same token counts, at a fixed, documented rate, and writes one durable row.",
    "Disabling that recorder still produces the framework's own trace and metric for a fresh call: only the row's absence actually proves whether the recorder ran.",
  ], { fontSize: 16 });
  addNotes(s,
    "When an agent calls a model, nobody can answer how much that cost or why it said what it " +
    "said from the response alone: a model call that returns a string gives no count of what went " +
    "in or out, no record of which model answered, and no trail once the process exits. Calling " +
    "an AiService method with an OpenTelemetry extension and a Micrometer registry present makes " +
    "the framework register its own listeners with no configuration naming them, one opening a " +
    "span carrying token-count attributes for the call and a child span for each tool it invokes, " +
    "the other recording a token-usage counter a metrics backend scrapes. The reason the durable " +
    "row has to be the check, not the metric, is that a metrics backend keeps a previous value " +
    "live across a restart, so a metric-presence check alone would pass whether or not the " +
    "recorder actually ran that call."
  );
}

{
  const s = S();
  addContentTitle(s, "AUDIT · OUTBOUND", "Every call our own code makes is audited before it leaves");
  addBullets(s, [
    "When an agent's own code calls another team's API, nobody on this side has a record unless the calling code writes one down itself.",
    "Logging the response after the fact is not enough: a call can fail to log while the call itself still went out and still had an effect on the other system.",
    "One chokepoint appends an audit record to an append-only log before the call goes out, so the call and its record can never come apart.",
    "Auditing that a call happened and recording what it returned are different concerns: disabling the audit step alone still lets the call succeed, with no new record behind it.",
  ], { fontSize: 16 });
  addNotes(s,
    "A call a team's own agent code makes outward, to a tool, a model or another service, can be " +
    "audited only if every path that makes such a call is forced through the same recording step; " +
    "scatter that responsibility across every call site, or leave it to a framework's own internal " +
    "tool-execution machinery, and a missed call site silently produces an unaudited outbound call " +
    "with no way to notice until someone asks for a record that was never written. The chokepoint " +
    "here is registered as the agent's only local tool rather than left to a framework's own " +
    "remote-tool auto-execution, and it always appends the audit record first, before performing " +
    "the call, publishing a correlation id, the target, and a digest of the argument, never the " +
    "raw argument itself, to an append-only log. Whichever path drives the call, the real model " +
    "choosing to call this local tool, or a fixture calling it directly with no model involved, " +
    "both reach the identical chokepoint, so the audit record is appended the same way regardless " +
    "of which one produced it."
  );
}

// ================================================================
// Section 06: FINAL
// ================================================================
divider("06", "Where this goes", "A focused, multi-domain data mesh",
  "The catalog closes with a final project that composes several of its own already-built " +
  "services into something larger than any one of them: three independently owned data " +
  "products, sharing one Kubernetes platform and one governance plane. This is a teaser here, " +
  "not a walkthrough, but it shows where everything in the earlier sections is " +
  "headed before we wrap up."
);

{
  const s = S();
  addDiagramSlide(s, "FINAL · THE CLOSING PROJECT", "Three data products, one governance plane", "38-final-project-data-mesh",
    "Orders, payments and shipping, each independently owned, deployed and governed, on the shared Kubernetes platform.");
  addNotes(s,
    "A catalog of separately built agent-facing services is not yet a mesh. Once two teams share " +
    "a cluster, one service's database can leak into another's with nothing to stop it, and no " +
    "single rule enforces schema compatibility across every contract an agent might encounter. " +
    "The final project assembles three such products, orders, payments and shipping, from " +
    "services already proven on their own, and asks one question of the result: when an agent " +
    "reads from one product, writes through another, and the second product's effect depends on " +
    "an event the first one caused, does the record each product keeps independently still add up " +
    "to one consistent story. None of the reused services publishes the payment-to-shipment event " +
    "on its own, so the scenario produces it directly against the cluster's own event broker, " +
    "standing in for an output port a production payment service would own, and the catalog names " +
    "that gap next to the product it belongs to."
  );
}

{
  const s = S();
  addContentTitle(s, "FINAL · WHAT IT PROVES", "One consistent story across three owners");
  addBullets(s, [
    "Each product owns one database in a shared cluster: domain ownership is a boundary a query can hit, not a label on a diagram.",
    "One aggregate manifest brings up or tears down every product together: a self-serve platform underneath each one, not a per-product chore.",
    "One compatibility rule, registered once against a shared schema registry, rejects a breaking change coming from any of the three products.",
    "The proof that matters: an agent reads from one product, writes through a second, and a third product's own independent record still matches what really happened.",
  ], { fontSize: 16 });
  addNotes(s,
    "Domain ownership, a self-serve platform and a live governance gate only matter if they hold " +
    "under that last question, not just in how the cluster is described on a diagram. The project " +
    "proves it by scaling the event-woken writer to zero and back: with the writer down, a paid " +
    "order sits unshipped and the gap is isolated to exactly the one deployment that was down, and " +
    "once the writer is back, it reads the event it missed and the row appears. A schema " +
    "registered under a backward-compatibility rule accepts a change that only adds an optional " +
    "field and rejects one that adds a required field with no default, live against the running " +
    "registry, the same rejection a reader would hit trying to ship a breaking change to any " +
    "product's contract. One rule, one registry, three products governed by it."
  );
}

// ================================================================
// Closing
// ================================================================
{
  const s = S();
  addContentTitle(s, "CLOSING · HOW TO READ THIS CATALOG", "One form, read the same way every time");
  addBullets(s, [
    "Every entry opens with a Name, states the Problem, then one bold Solution sentence, concrete enough to apply without reading further.",
    "A handful of principles are named once and invoked throughout: the contract is the instruction set, resolve ambiguity in the backend, the model reasons while the application controls, budget the context, prefer few high-impact tools over many granular ones, prefer instructive errors over opaque codes.",
    "Build, run, observe always ends in a negative witness, a check that fails once the pattern's own guard is removed, never a check that only ever passes.",
    "Reaching for a tool-calling protocol by default, for every integration regardless of shape, is itself a named anti-pattern: a well-documented command-line tool or a plain function call often does the job just as well.",
  ], { fontSize: 15.5 });
  addNotes(s,
    "The point of naming the same handful of principles and anti-patterns once, here, is that no " +
    "later pattern page has to re-derive them: naive API-to-MCP wrapping, tool inflation, chatty " +
    "CRUD for agents, opaque errors, MCP by default, passing raw identifiers through the context " +
    "window, prompt as business logic, and lethal-trifecta wiring are all named so a page can " +
    "point at what it argues against without restating the argument. If you take one habit away " +
    "from this talk, make it this: read a tool's name, description and schema the way the agent " +
    "actually has to, as the entire instruction set it gets, and ask what a vague version of any " +
    "one of those three would cost on the very first call."
  );
}

{
  const s = S();
  addContentTitle(s, "CLOSING · REFERENCES", "Sources this catalog leans on");
  addBullets(s, [
    "Gregor Hohpe and Bobby Woolf, Enterprise Integration Patterns (Addison-Wesley, 2003): the pattern-catalog form and spine this catalog follows.",
    "Model Context Protocol specification, the Linux Foundation-hosted MCP working group: tools, resources, prompts and the transports every MCP-packaged pattern targets.",
    "JJ Geewax, API Design Patterns (Manning, 2021): resource-oriented design and the error-model patterns reshaped here for agent consumption.",
    "Zhamak Dehghani, Data Mesh: Delivering Data-Driven Value at Scale (O'Reilly, 2022): the four principles behind the final project's data products.",
    "Chip Huyen, AI Engineering (O'Reilly, 2024): the practitioner framing for tool use and context engineering this catalog's taxonomy draws on.",
    "Simon Willison, The lethal trifecta for AI agents (2025): private data, untrusted content and an exit path, combined into one exfiltration risk.",
  ], { fontSize: 15 });
  addNotes(s,
    "None of this catalog's text or code is reproduced from any of these works; they are reference " +
    "works that shaped what the catalog covers and how rigorously it treats each pattern, while " +
    "the project and runtime documentation for Quarkus, Camel, Kafka and the Model Context " +
    "Protocol itself is the authority for every API the examples actually use. The Model Context " +
    "Protocol specification in particular is vendor-neutral: it is not any one vendor's private " +
    "protocol, which is exactly why it works as a facade over services built independently on " +
    "REST, GraphQL and gRPC. Thank you, and the full catalog, every pattern, every runnable " +
    "example, is the place to go deeper on anything covered here today."
  );
}

pres.writeFile({ fileName: OUT })
  .then(p => console.log("WROTE", p))
  .catch(e => { console.error(e); process.exit(1); });
