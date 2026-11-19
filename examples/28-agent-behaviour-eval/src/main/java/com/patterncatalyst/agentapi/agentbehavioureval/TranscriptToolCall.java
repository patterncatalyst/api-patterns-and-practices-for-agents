package com.patterncatalyst.agentapi.agentbehavioureval;

import java.util.Map;

/**
 * One captured tool call: the name the agent invoked, its arguments (captured for the audit
 * record, never matched literally by {@link BehaviourEvaluator}, since free-text arguments like a
 * refund reason vary even on a compliant run), and whether that call's own result was an argument
 * error. Read from {@code dev.langchain4j.service.Result#toolExecutions()} on the LIVE path, or
 * from a {@code tool_call} line in a committed JSON-lines transcript on the replay path; {@link
 * BehaviourEvaluator} scores both the same way.
 */
public record TranscriptToolCall(String name, Map<String, Object> args, boolean argError) {
}
