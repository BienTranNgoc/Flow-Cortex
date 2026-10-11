package com.flowcortex.user.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Wires the {@link JwtDecoder} used to verify Google {@code id_token}s against
 * Google's JWKS endpoint. The decoder is qualified by name so it does not
 * collide with the user module's own HS256 access-token decoder.
 */
@Configuration(proxyBeanMethods = false)
public class GoogleIdTokenDecoderConfig {

    @Bean(name = "googleIdTokenDecoder")
    JwtDecoder googleIdTokenDecoder() {
        return NimbusJwtDecoder.withJwkSetUri("https://www.googleapis.com/oauth2/v3/certs").build();
    }
}
