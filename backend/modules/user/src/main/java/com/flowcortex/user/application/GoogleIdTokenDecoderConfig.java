package com.flowcortex.user.application;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestTemplate;

/**
 * Wires {@link GoogleOAuthClient}. The {@code id_token} decoder is built here and handed to the client
 * directly instead of being a {@code JwtDecoder} bean, so it can never be picked up in place of the
 * access-token decoder the security chains inject by type. Calls to Google, JWKS fetch included,
 * run under the configured connect/read timeouts.
 */
@Configuration(proxyBeanMethods = false)
public class GoogleIdTokenDecoderConfig {

    private static final String JWKS_URI = "https://www.googleapis.com/oauth2/v3/certs";

    @Bean
    GoogleOAuthClient googleOAuthClient(GoogleOAuthProperties properties) {
        SimpleClientHttpRequestFactory requests = new SimpleClientHttpRequestFactory();
        requests.setConnectTimeout(properties.connectTimeout());
        requests.setReadTimeout(properties.readTimeout());

        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(JWKS_URI)
                .restOperations(new RestTemplate(requests))
                .build();
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(new JwtTimestampValidator(),
                new GoogleIdTokenValidator(properties.clientId())));

        return new GoogleOAuthClient(properties, decoder, RestClient.builder().requestFactory(requests).build());
    }
}
