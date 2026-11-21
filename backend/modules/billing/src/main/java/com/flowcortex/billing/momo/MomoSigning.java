package com.flowcortex.billing.momo;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/** Builds the string MoMo signs and the HMAC-SHA256 hex over it. */
public final class MomoSigning {

    private static final String HMAC = "HmacSHA256";

    private MomoSigning() {
    }

    /**
     * Renders {@code name=value} for each declared field, joined by {@code &}. Text is taken verbatim and an
     * integral number as its digits; an absent, null, boolean, fractional or structured value is refused.
     */
    public static String signingString(String declaration, List<String> fields, ObjectNode values) {
        return fields.stream()
                .map(field -> field + "=" + render(declaration, field, values.get(field)))
                .collect(Collectors.joining("&"));
    }

    private static String render(String declaration, String field, JsonNode value) {
        if (value == null || value.isNull() || value.isMissingNode()) {
            throw new MomoSigningException(MomoSigningException.Kind.MISSING_FIELD, declaration, field);
        }
        if (value.isString()) {
            return value.asString();
        }
        if (value.isIntegralNumber()) {
            return value.bigIntegerValue().toString();
        }
        throw new MomoSigningException(MomoSigningException.Kind.UNSUPPORTED_VALUE, declaration, field);
    }

    /** Lower-case hex HMAC-SHA256 of {@code text} under {@code secretKey}. */
    public static String hmacHex(String text, String secretKey) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), HMAC));
            return HexFormat.of().formatHex(mac.doFinal(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable or the key is unusable", e);
        }
    }

    /** Constant-time comparison of two signatures; false when either is null or the lengths differ. */
    public static boolean matches(String expectedHex, String presentedHex) {
        if (expectedHex == null || presentedHex == null) {
            return false;
        }
        byte[] expected = expectedHex.getBytes(StandardCharsets.UTF_8);
        byte[] presented = presentedHex.getBytes(StandardCharsets.UTF_8);
        return expected.length == presented.length && MessageDigest.isEqual(expected, presented);
    }
}
