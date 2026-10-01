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
 * Stateless JWT resource-server filter chain: validates a locally-signed HS256 token, no
 * session, method security enabled so modules can guard endpoints with {@code @PreAuthorize}.
 *
 * <p>Renders no response body itself: an unauthenticated/forbidden request goes through Spring
 * MVC's {@link HandlerExceptionResolver} chain — the same one :shared:web's global {@code
 * @RestControllerAdvice} participates in — so 401/403 come back as {@code
 * application/problem+json} without :shared:security depending on :shared:web.
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
                        // Servlet ERROR forwards are re-evaluated by the chain; without this an
                        // anonymous request failing downstream (bad path var, missing param) and
                        // landing on /error comes back 401 instead of its real status. Only the
                        // internal forward is permitted — a direct GET /error is still denied.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Liveness/readiness probes + committed OpenAPI snapshot/Swagger UI stay
                        // anonymous — public read-only artifacts the contract pipeline doesn't need a
                        // token for. The actuator lives on the management port, not here.
                        .requestMatchers("/livez", "/readyz").permitAll()
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
