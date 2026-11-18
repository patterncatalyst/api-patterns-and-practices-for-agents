package com.patterncatalyst.agentapi.idempotencysaferetries;

/** An instructive error body: what is wrong, not an opaque code. Served as application/problem+json. */
public record Problem(String title, int status, String detail) {
}
