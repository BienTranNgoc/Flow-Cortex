package com.flowcortex.user.web;

import com.flowcortex.shared.security.RoleClaimJwtConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security policy for this module's HTTP surface ({@code /api/v1/auth}, {@code /api/v1/users})
 * — a second, narrowly-matched {@link SecurityFilterChain} evaluated before {@code
 * :shared:security}'s broad default chain. Needed because the shared chain can't express two
 * things without editing its internals:
 * <ol>
 *   <li>{@code POST /api/v1/auth/login} must be reachable pre-auth (shared chain's
 *   {@code anyRequest().authenticated()} would block it).</li>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} on {@code UsersController} needs the JWT's
 *   {@code role} claim mapped to a {@code ROLE_*} authority ({@link RoleClaimJwtConverter}) — the
 *   shared chain's default converter reads OAuth2 {@code scope}/{@code scp} instead.</li>
 * </ol>
 *
 * <p>Reuses the shared chain's {@link JwtDecoder} and {@code HandlerExceptionResolver} beans,
 * so 401/403 render through the same {@code application/problem+json} contract.
 */
@Configuration
@EnableWebSecurity
class UserModuleSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain userModuleSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, authException) ->
                exceptionResolver.resolveException(request, response, null, authException);

        http
                .securityMatcher("/api/v1/auth/**", "/api/v1/users/**")
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(request -> {
                    org.springframework.web.cors.CorsConfiguration config = new org.springframework.web.cors.CorsConfiguration();
                    config.setAllowedOrigins(java.util.List.of("http://localhost:5173"));
                    config.setAllowedMethods(java.util.List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
                    config.setAllowedHeaders(java.util.List.of("*"));
                    config.setExposedHeaders(java.util.List.of("X-Request-Id"));
                    config.setAllowCredentials(true);
                    return config;
                }))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register").permitAll()
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/refresh").permitAll()
                        .requestMatchers("/api/v1/auth/oauth/google/**").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(new RoleClaimJwtConverter())));
        return http.build();
    }
}
