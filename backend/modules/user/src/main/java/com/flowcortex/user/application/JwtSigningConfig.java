package com.flowcortex.user.application;

import javax.crypto.SecretKey;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/**
 * Builds the {@link JwtEncoder} this module uses to mint login tokens, reusing the same signing
 * key {@code :shared:security} manages ({@code JwtKeyConfig}'s {@code SecretKey} bean, injected
 * by type — a plain JDK type, so this is bean consumption, not a reference into that module).
 *
 * <p>Symmetric with {@code JwtKeyConfig#jwtDecoder}: same key, same algorithm (HS256).
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
