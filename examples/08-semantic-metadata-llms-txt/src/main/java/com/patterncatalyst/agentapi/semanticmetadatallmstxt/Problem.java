package com.patterncatalyst.agentapi.semanticmetadatallmstxt;

import org.eclipse.microprofile.openapi.annotations.media.Schema;

/** An instructive error body: what is wrong, not an opaque code. Served as application/problem+json. */
public record Problem(
        @Schema(description = "A short, human-readable summary of the problem.") String title,
        @Schema(description = "The HTTP status code.") int status,
        @Schema(description = "A detail specific to this occurrence of the problem.") String detail) {
}
