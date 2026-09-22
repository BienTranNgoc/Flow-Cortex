package com.flowcortex.shared.security;

import java.nio.charset.StandardCharsets;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Builds the HS256 signing/verification key from the {@code JWT_SECRET}
 * environment variable (design.md D4). The base {@code application.yml}
 * supplies no fallback — only the {@code dev} Spring profile does, in
 * {@code application-dev.yml}, so a real deployment that forgets to set
 * {@code JWT_SECRET} fails to start instead of silently signing tokens with a
 * weak or shared key.
 */
@Configuration
public class JwtKeyConfig {

    /** HS256 requires a key of at least 256 bits (RFC 7518 §3.2). */
    static final int MIN_SECRET_BYTES = 32;

    @Bean
    public SecretKey jwtSigningKey(@Value("${flowcortex.security.jwt-secret:}") String secret) {
        return buildKey(secret);
    }

    @Bean
    public JwtDecoder jwtDecoder(SecretKey jwtSigningKey) {
        return NimbusJwtDecoder.withSecretKey(jwtSigningKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
    }

    /**
     * Package-visible so {@code JwtKeyConfigTest} can exercise the validation
     * logic directly, without a Spring context.
     */
    static SecretKey buildKey(String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("JWT_SECRET is not set. Set the JWT_SECRET environment variable "
                    + "(a dev-only default is supplied by the 'dev' Spring profile).");
        }
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        if (keyBytes.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT_SECRET must be at least " + MIN_SECRET_BYTES + " bytes for HS256 signing.");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }
}
