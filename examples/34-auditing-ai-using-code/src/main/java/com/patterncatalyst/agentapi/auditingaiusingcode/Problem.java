package com.patterncatalyst.agentapi.auditingaiusingcode;

/** A minimal RFC 9457-shaped problem body for the two rejections this chapter's resource makes. */
public record Problem(String title, int status, String detail) {
}
