package com.patterncatalyst.agentapi.auditingaiusingcode;

/**
 * Demo-only, gated by {@code audit.mutable}: names a committed record and the argument digest to
 * overwrite it with, in place, never appending a correcting record to the log.
 */
public record MutateRequest(String id, String newArgumentDigest) {
}
