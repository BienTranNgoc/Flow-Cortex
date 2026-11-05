package com.flowcortex.support.assistant;

import com.flowcortex.support.config.SupportProperties;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.HexFormat;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Hex HMAC-SHA256 of a guest's session id and network address under
 * {@code flowcortex.support.assistant.hash-secret}. A plain digest would not do: the IPv4 space can be
 * enumerated. Changing the secret orphans guest conversations and restarts guest quotas.
 */
@Component
class GuestKeys {

    static final int MIN_SECRET_BYTES = 32;

    private static final String HMAC = "HmacSHA256";

    private final SecretKey key;

    /** Fails startup when the assistant is on and the secret is blank or shorter than 32 bytes. */
    GuestKeys(SupportProperties properties, @Value("${flowcortex.support.assistant.hash-secret:}") String secret) {
        byte[] keyBytes = secret == null || secret.isBlank() ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length >= MIN_SECRET_BYTES) {
            this.key = new SecretKeySpec(keyBytes, HMAC);
        } else if (properties.assistant().enabled()) {
            throw new IllegalStateException("flowcortex.support.assistant.enabled is true but "
                    + "flowcortex.support.assistant.hash-secret (FLOWCORTEX_SUPPORT_HASH_SECRET) is "
                    + (keyBytes.length == 0 ? "not set" : "shorter than " + MIN_SECRET_BYTES + " bytes")
                    + ". Set it to at least " + MIN_SECRET_BYTES + " random characters.");
        } else {
            this.key = null;
        }
    }

    /** False only while the assistant is off and no secret is set; no guest key can be made then. */
    boolean configured() {
        return key != null;
    }

    Asker.Guest guest(String session, String networkAddress) {
        return new Asker.Guest(hash(session), hash(networkAddress));
    }

    private String hash(String value) {
        if (key == null) {
            throw new IllegalStateException("flowcortex.support.assistant.hash-secret is not set");
        }
        try {
            Mac mac = Mac.getInstance(HMAC);
            mac.init(key);
            return HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("HmacSHA256 is not available", e);
        }
    }
}
