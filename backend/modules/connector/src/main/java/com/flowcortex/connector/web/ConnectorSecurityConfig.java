package com.flowcortex.connector.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Google's redirect arrives as a plain browser navigation that cannot carry a bearer token; the sealed flow cookie
 * is the credential. The chain matches only {@code GET} on the callback path, so every other connector route stays
 * on the shared catch-all chain.
 */
@Configuration(proxyBeanMethods = false)
class ConnectorSecurityConfig {

    @Bean
    @Order(12)
    SecurityFilterChain connectorCallbackSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.GET, GoogleConnectorController.CALLBACK_PATH))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
