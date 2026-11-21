package com.flowcortex.workflow.web;

import com.flowcortex.shared.security.RoleClaimJwtConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * {@code GET /api/v1/workflows/by-key/**} answers without a token; every other workflow route stays on the shared
 * catch-all chain. A token that is sent is still validated, so an invalid one is 401 rather than silently anonymous.
 */
@Configuration(proxyBeanMethods = false)
class WorkflowShareSecurityConfig {

    @Bean
    @Order(7)
    SecurityFilterChain workflowShareSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, authException) ->
                exceptionResolver.resolveException(request, response, null, authException);

        http
                .securityMatcher(PathPatternRequestMatcher.withDefaults()
                        .matcher(HttpMethod.GET, "/api/v1/workflows/by-key/**"))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(new RoleClaimJwtConverter())));
        return http.build();
    }
}
