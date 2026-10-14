package com.flowcortex.user.application;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * Seals an {@link OAuthFlowState} into a cookie value ({@code fields.signature}) and opens it again.
 * The HMAC key is derived from the JWT signing secret under a fixed label, so it needs no
 * configuration of its own and an access token can never double as a flow cookie.
 */
@Component
public class OAuthFlowCookieCodec {

    private static final String HMAC = "HmacSHA256";

    private static final String KEY_LABEL = "flowcortex.oauth.flow-cookie.v1";

    private static final int FIELDS = 6;

    private static final Base64.Encoder ENCODER = Base64.getUrlEncoder().withoutPadding();

    private static final Base64.Decoder DECODER = Base64.getUrlDecoder();

    private final SecretKey key;

    private final Clock clock;

    public OAuthFlowCookieCodec(SecretKey jwtSigningKey, Clock clock) {
        this.key = new SecretKeySpec(hmac(jwtSigningKey, KEY_LABEL.getBytes(StandardCharsets.UTF_8)), HMAC);
        this.clock = clock;
    }

    public String seal(OAuthFlowState flow) {
        String fields = String.join(".", flow.state(), flow.codeVerifier(), flow.nonce(),
                ENCODER.encodeToString(flow.returnTo().getBytes(StandardCharsets.UTF_8)),
                flow.rememberMe() ? "1" : "0", Long.toString(flow.expiresAt().getEpochSecond()));
        return fields + "." + ENCODER.encodeToString(hmac(key, fields.getBytes(StandardCharsets.US_ASCII)));
    }

    /** Empty unless the value carries a valid signature and has not expired. */
    public Optional<OAuthFlowState> open(String sealed) {
        if (sealed == null) {
            return Optional.empty();
        }
        int split = sealed.lastIndexOf('.');
        if (split < 0) {
            return Optional.empty();
        }
        String fields = sealed.substring(0, split);
        try {
            byte[] signature = DECODER.decode(sealed.substring(split + 1));
            byte[] expected = hmac(key, fields.getBytes(StandardCharsets.US_ASCII));
            if (!MessageDigest.isEqual(signature, expected)) {
                return Optional.empty();
            }
            String[] parts = fields.split("\\.", -1);
            if (parts.length != FIELDS) {
                return Optional.empty();
            }
            Instant expiresAt = Instant.ofEpochSecond(Long.parseLong(parts[5]));
            if (!Instant.now(clock).isBefore(expiresAt)) {
                return Optional.empty();
            }
            String returnTo = new String(DECODER.decode(parts[3]), StandardCharsets.UTF_8);
            return Optional.of(new OAuthFlowState(parts[0], parts[1], parts[2], returnTo, "1".equals(parts[4]),
                    expiresAt));
        } catch (IllegalArgumentException | java.time.DateTimeException ex) {
            return Optional.empty();
        }
    }

    private static byte[] hmac(SecretKey key, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(new SecretKeySpec(key.getEncoded(), HMAC));
            return mac.doFinal(data);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException(HMAC + " is unavailable", ex);
        }
    }
}
