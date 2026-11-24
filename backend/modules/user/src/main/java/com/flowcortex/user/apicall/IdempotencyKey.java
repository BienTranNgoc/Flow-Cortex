package com.flowcortex.user.apicall;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/** The {@code Idempotency-Key} header rules and the fingerprint that tells a retry from a different request. */
final class IdempotencyKey {

    static final String HEADER = "Idempotency-Key";
    static final int MAX_LENGTH = 80;

    private static final Set<String> KEYED_METHODS = Set.of("POST", "PATCH");

    private IdempotencyKey() {}

    /** The trimmed key, empty when it is blank or longer than {@value #MAX_LENGTH} characters. */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        String key = raw.strip();
        return key.isEmpty() || key.length() > MAX_LENGTH ? Optional.empty() : Optional.of(key);
    }

    static boolean appliesTo(String method) {
        return KEYED_METHODS.contains(method);
    }

    /** A multipart body or one over {@code maxBytes} cannot be fingerprinted from memory. */
    static boolean unsupported(String contentType, long bodyLength, long maxBytes) {
        boolean multipart = contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("multipart/");
        return multipart || bodyLength > maxBytes;
    }

    /** SHA-256 hex of method, path, raw query (empty when none) and body bytes. */
    static String fingerprint(String method, String path, String rawQuery, byte[] body) {
        MessageDigest digest = sha256();
        String head = method + "\n" + path + "?" + (rawQuery == null ? "" : rawQuery) + "\n";
        digest.update(head.getBytes(StandardCharsets.UTF_8));
        digest.update(body);
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required by the platform", ex);
        }
    }
}
