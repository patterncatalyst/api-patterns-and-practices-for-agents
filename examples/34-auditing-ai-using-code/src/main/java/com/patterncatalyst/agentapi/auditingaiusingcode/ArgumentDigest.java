package com.patterncatalyst.agentapi.auditingaiusingcode;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * A one-way digest of an outbound call's arguments, never the raw arguments themselves, so the
 * append-only log can prove which call happened and with what input without becoming a second
 * place a caller's actual argument values are durably stored.
 */
final class ArgumentDigest {

    static String sha256Hex(String argument) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(argument.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private ArgumentDigest() {
    }
}
