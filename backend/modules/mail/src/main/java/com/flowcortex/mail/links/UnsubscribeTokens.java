package com.flowcortex.mail.links;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Signs and verifies {@code <linkId>.<base64url HMAC-SHA256(linkId|list)>}. Verification is pure: it
 * checks the shape, then the signature against both lists in constant time, and reads nothing.
 */
public final class UnsubscribeTokens {

    /** A token that checked out: the link row it names and the list it unsubscribes from. */
    public record Verified(UUID linkId, LinkList list) {
    }

    private static final String HMAC = "HmacSHA256";
    private static final Pattern UUID_SHAPE =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final byte[] secret;

    public UnsubscribeTokens(String secret) {
        this.secret = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
    }

    /** Whether a secret is configured; without one nothing can be minted and every token is invalid. */
    public boolean configured() {
        return secret.length > 0;
    }

    public String mint(UUID linkId, LinkList list) {
        if (!configured()) {
            throw new IllegalStateException("flowcortex.mail.link-secret is not set; no unsubscribe link can be signed");
        }
        return linkId + "." + ENCODER.encodeToString(sign(linkId, list));
    }

    public Optional<Verified> verify(String token) {
        if (!configured() || token == null) {
            return Optional.empty();
        }
        int separator = token.indexOf('.');
        if (separator < 1 || separator == token.length() - 1) {
            return Optional.empty();
        }
        String id = token.substring(0, separator);
        if (!UUID_SHAPE.matcher(id).matches()) {
            return Optional.empty();
        }
        String encoded = token.substring(separator + 1);
        byte[] presented;
        try {
            presented = DECODER.decode(encoded);
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
        // The decoder ignores the unused trailing bits, so only the canonical spelling is accepted.
        if (!ENCODER.encodeToString(presented).equals(encoded)) {
            return Optional.empty();
        }
        UUID linkId = UUID.fromString(id);
        // Both lists are always compared, so the time taken does not reveal which one matched.
        boolean care = MessageDigest.isEqual(presented, sign(linkId, LinkList.CARE));
        boolean marketing = MessageDigest.isEqual(presented, sign(linkId, LinkList.MARKETING));
        if (care) {
            return Optional.of(new Verified(linkId, LinkList.CARE));
        }
        return marketing ? Optional.of(new Verified(linkId, LinkList.MARKETING)) : Optional.empty();
    }

    private byte[] sign(UUID linkId, LinkList list) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secret, HMAC));
            return mac.doFinal((linkId + "|" + list.wire()).getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("HmacSHA256 is unavailable", ex);
        }
    }
}
