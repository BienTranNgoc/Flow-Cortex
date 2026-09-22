package com.flowcortex.user.application;

import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Builds the {@link JwtEncoder} this module uses to mint login tokens
 * (design.md D4), reusing the SAME signing key {@code :shared:security}
 * already manages ({@code JwtKeyConfig}'s {@code SecretKey} bean, injected
 * here by type — {@code javax.crypto.SecretKey} is a JDK type, not a {@code
 * com.flowcortex.shared.security} one, so this is a plain bean consumption,
 * not a reference into that module's internals).
 *
 * <p>Symmetric with {@code JwtKeyConfig#jwtDecoder}: same key, same algorithm
 * (HS256), one side encodes and the other decodes.
 */
@Configuration
class JwtSigningConfig {

    @Bean
    JwtEncoder jwtEncoder(SecretKey jwtSigningKey) {
        return NimbusJwtEncoder.withSecretKey(jwtSigningKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }
}
