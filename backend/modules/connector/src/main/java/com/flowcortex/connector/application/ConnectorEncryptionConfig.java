package com.flowcortex.connector.application;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the cipher from the keyring and fails startup, in every profile, when the keyring is
 * unusable. Messages name the setting, never a key value.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ConnectorEncryptionProperties.class)
class ConnectorEncryptionConfig {

    static final int KEY_BYTES = 32;
    static final Pattern KEY_ID = Pattern.compile("[a-z0-9]{1,16}");

    private static final String CURRENT_SETTING = "FLOWCORTEX_CONNECTORS_ENCRYPTION_CURRENT";
    private static final String KEYS_SETTING = "FLOWCORTEX_CONNECTORS_ENCRYPTION_KEYS_";

    @Bean
    CredentialCipher credentialCipher(ConnectorEncryptionProperties properties) {
        return buildCipher(properties.current(), properties.keys());
    }

    /** Package-visible so the key test can exercise the validation without a Spring context. */
    static CredentialCipher buildCipher(String current, Map<String, String> keys) {
        if (current == null || current.isBlank()) {
            throw new IllegalStateException(CURRENT_SETTING + " is not set. Set it to the id of the key "
                    + "that encrypts new credentials, e.g. k1.");
        }
        if (keys == null || keys.isEmpty()) {
            throw new IllegalStateException("No connector encryption key is set. Set " + KEYS_SETTING
                    + "<ID> (32 random bytes in base64) for every key id, including " + current + ".");
        }
        Map<String, SecretKey> ring = new LinkedHashMap<>();
        keys.forEach((id, value) -> ring.put(id, parseKey(id, value)));
        if (!ring.containsKey(current)) {
            throw new IllegalStateException(CURRENT_SETTING + " names key id '" + current
                    + "' but no " + KEYS_SETTING + current.toUpperCase(Locale.ROOT) + " is set.");
        }
        return new CredentialCipher(current, ring);
    }

    private static SecretKey parseKey(String id, String value) {
        if (!KEY_ID.matcher(id).matches()) {
            throw new IllegalStateException("Connector encryption key id '" + id + "' must match "
                    + KEY_ID.pattern() + ".");
        }
        String setting = KEYS_SETTING + id.toUpperCase(Locale.ROOT);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(setting + " is blank.");
        }
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(value.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException(setting + " is not valid base64.");
        }
        if (bytes.length != KEY_BYTES) {
            throw new IllegalStateException(setting + " must decode to exactly " + KEY_BYTES
                    + " bytes (generate one with: openssl rand -base64 32).");
        }
        return new SecretKeySpec(bytes, "AES");
    }
}
