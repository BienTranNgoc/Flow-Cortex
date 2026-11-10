package com.flowcortex.workflow.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Dedupe key for an anonymous reader of a shared workflow: HMAC-SHA256 of the full address and user
 * agent under {@code flowcortex.workflow.share.accessor-hash-secret}. A plain digest would not do:
 * the IPv4 space can be enumerated. Changing the secret only lets each reader log once more.
 */
@Component
class AccessorKeys {

    static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC = "HmacSHA256";

    private final SecretKey key;

    /** Fails startup when the secret is blank or shorter than 32 bytes. */
    AccessorKeys(@Value("${flowcortex.workflow.share.accessor-hash-secret:}") String secret) {
        byte[] keyBytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException("flowcortex.workflow.share.accessor-hash-secret "
                    + "(FLOWCORTEX_WORKFLOW_SHARE_HASH_SECRET) is "
                    + (keyBytes.length == 0 ? "not set" : "shorter than " + MIN_SECRET_BYTES + " bytes")
                    + ". Set it to at least " + MIN_SECRET_BYTES + " random characters.");
        }
        this.key = new SecretKeySpec(keyBytes, HMAC);
    }

    /** {@code n:} plus 64 hex characters; the address and agent are separated so they cannot blur together. */
    String anonymous(String remoteAddr, String userAgent) {
        String input = (remoteAddr == null ? "" : remoteAddr) + "\n" + (userAgent == null ? "" : userAgent);
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return "n:" + HexFormat.of().formatHex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
