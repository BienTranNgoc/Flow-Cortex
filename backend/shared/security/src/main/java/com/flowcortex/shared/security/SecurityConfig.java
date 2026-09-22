package com.flowcortex.shared.security;

import jakarta.servlet.DispatcherType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Stateless JWT resource-server filter chain (design.md D4): validates a
 * locally-signed HS256 token, no session, method security enabled so modules
 * can guard endpoints with {@code @PreAuthorize} (group 4 — R-023).
 *
 * <p>Deliberately renders no response body itself: an unauthenticated or
 * forbidden request is handed to Spring MVC's {@link HandlerExceptionResolver}
 * chain, which is exactly the chain :shared:web's global
 * {@code @RestControllerAdvice} participates in — so 401/403 come back in the
 * same {@code application/problem+json} shape as every other error (D3),
 * without :shared:security depending on :shared:web at all.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        AuthenticationEntryPoint entryPoint =
                (request, response, authException) -> exceptionResolver.resolveException(
                        request, response, null, authException);

        http
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Servlet ERROR forwards are re-evaluated by the filter
                        // chain, so an anonymous request that fails downstream
                        // (bad path variable, missing param) and lands on /error
                        // would otherwise come back 401 instead of the real
                        // status. Only the internal forward is permitted here —
                        // a direct GET /error is still denied by anyRequest().
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Health probe stays anonymous; the committed OpenAPI
                        // snapshot doc + its Swagger UI are public read-only
                        // artifacts (design.md D6, task 3.1) so the contract
                        // pipeline's snapshot test doesn't need a token — the
                        // auth/login + per-route rules land in group 4.
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**").permitAll()
                        .requestMatchers("/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder)));
        return http.build();
    }
}
