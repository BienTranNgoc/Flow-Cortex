package com.flowcortex.app.clienterrors;

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
 * {@code POST /api/v1/client-errors} answers anonymously. The chain has no resource server, so an
 * expired or malformed bearer token is never looked at and cannot turn into a 401 for a page that
 * is already failing. Any other method on the path falls through to the shared catch-all.
 */
@Configuration(proxyBeanMethods = false)
class ClientErrorsSecurityConfig {

    @Bean
    @Order(8)
    SecurityFilterChain clientErrorsSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.POST, "/api/v1/client-errors"))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
