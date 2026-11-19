package com.patterncatalyst.agentapi.replayableagentmemoryeventsourcedaudit;

/**
 * Demo-only, gated by {@code audit.mutable}: names a committed record and the detail to overwrite
 * it with, in place, never appending a correcting record to the log.
 */
public record MutateRequest(String id, String newDetail) {
}
