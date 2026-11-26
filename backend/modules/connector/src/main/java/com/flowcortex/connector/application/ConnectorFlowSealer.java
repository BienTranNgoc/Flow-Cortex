package com.flowcortex.connector.application;

import com.flowcortex.connector.api.CredentialUnreadableException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Seals the state of an OAuth connect flow into one opaque value for the browser's cookie, with the connector
 * keyring: the member, the {@code state}, the PKCE verifier and the expiry are readable only by this backend, and
 * a value made for a stored credential does not open as a flow. Replaying a captured value is stopped by the
 * provider's one-time authorization code, not here.
 */
@Component
public class ConnectorFlowSealer {

    private static final UUID NO_MEMBER = new UUID(0L, 0L);
    private static final String PURPOSE = "google-oauth-flow";
    private static final String SEPARATOR = "|";
    private static final int RANDOM_BYTES = 32;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The secrets of one started connect; {@code toString} omits them. */
    public record Flow(UUID memberId, String state, String verifier, Instant expiresAt) {

        /** A new flow for {@code memberId}: a fresh {@code state} and verifier, expiring {@code ttl} from now. */
        public static Flow begin(UUID memberId, Duration ttl, Clock clock) {
            return new Flow(memberId, randomToken(), randomToken(), clock.instant().plus(ttl));
        }

        /** The PKCE S256 challenge of the verifier. */
        public String codeChallenge() {
            try {
                byte[] digest = MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII));
                return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
            } catch (NoSuchAlgorithmException ex) {
                throw new IllegalStateException("SHA-256 is not available", ex);
            }
        }

        @Override
        public String toString() {
            return "Flow[expiresAt=" + expiresAt + "]";
        }

        private static String randomToken() {
            byte[] bytes = new byte[RANDOM_BYTES];
            RANDOM.nextBytes(bytes);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        }
    }

    private final CredentialCipher cipher;

    ConnectorFlowSealer(CredentialCipher cipher) {
        this.cipher = cipher;
    }

    public String seal(Flow flow) {
        String plain = String.join(SEPARATOR, flow.memberId().toString(), flow.state(), flow.verifier(),
                Long.toString(flow.expiresAt().getEpochSecond()));
        return cipher.encrypt(NO_MEMBER, PURPOSE, plain);
    }

    /** The flow, or empty when the value is missing, was not sealed here, was altered, or is not before {@code now}. */
    public Optional<Flow> open(String value, Instant now) {
        if (value == null || value.isBlank()) {
            return Optional.empty();
        }
        String plain;
        try {
            plain = cipher.decrypt(NO_MEMBER, PURPOSE, value);
        } catch (CredentialUnreadableException ex) {
            return Optional.empty();
        }
        String[] parts = plain.split("\\" + SEPARATOR, -1);
        if (parts.length != 4) {
            return Optional.empty();
        }
        try {
            Flow flow = new Flow(UUID.fromString(parts[0]), parts[1], parts[2],
                    Instant.ofEpochSecond(Long.parseLong(parts[3])));
            return now.isBefore(flow.expiresAt()) ? Optional.of(flow) : Optional.empty();
        } catch (IllegalArgumentException | DateTimeException ex) {
            return Optional.empty();
        }
    }
}
