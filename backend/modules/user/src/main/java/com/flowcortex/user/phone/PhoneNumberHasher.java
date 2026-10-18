package com.flowcortex.user.phone;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hex HMAC-SHA256 of an E.164 number under {@code flowcortex.phone.hash-secret}: how {@code
 * phone_send_attempt} tells numbers apart. A plain digest would not do, because the whole VN mobile
 * range can be enumerated. Changing the secret makes earlier rows stop matching, so quotas restart.
 */
@Component
public class PhoneNumberHasher {

    static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC = "HmacSHA256";

    private final SecretKey key;

    /** Fails startup when phone verification is on and the secret is blank or shorter than 32 bytes. */
    PhoneNumberHasher(PhoneHashProperties properties,
            @Value("${flowcortex.phone-verification.enabled:false}") boolean enabled) {
        String secret = properties.hashSecret();
        byte[] keyBytes = secret == null || secret.isBlank() ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length >= MIN_SECRET_BYTES) {
            this.key = new SecretKeySpec(keyBytes, HMAC);
        } else if (enabled) {
            throw new IllegalStateException("flowcortex.phone-verification.enabled is true but "
                    + "flowcortex.phone.hash-secret (FLOWCORTEX_PHONE_HASH_SECRET) is "
                    + (keyBytes.length == 0 ? "not set" : "shorter than " + MIN_SECRET_BYTES + " bytes")
                    + ". Set it to at least " + MIN_SECRET_BYTES + " random characters.");
        } else {
            this.key = null;
        }
    }

    public String hash(String e164) {
        if (key == null) {
            throw new IllegalStateException("flowcortex.phone.hash-secret is not set");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(e164.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
