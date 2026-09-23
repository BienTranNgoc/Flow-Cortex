package com.flowcortex.user.web;

import com.flowcortex.user.application.JwtClaims;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Security policy for THIS module's own HTTP surface — {@code /api/v1/auth}
 * and {@code /api/v1/users} — layered as a SECOND {@link SecurityFilterChain}
 * narrowly matched to those two path prefixes, evaluated before {@code
 * :shared:security}'s broad, unordered (so lowest-precedence, evaluated last
 * / fallback) default chain.
 *
 * <p>This is deliberate, not a workaround: two things this module's own
 * endpoints need cannot be expressed by adding to {@code :shared:security}'s
 * existing chain without editing that module's internals:
 * <ol>
 *   <li>{@code POST /api/v1/auth/login} must be reachable pre-auth — the
 *   shared chain's {@code anyRequest().authenticated()} would otherwise
 *   block it.</li>
 *   <li>{@code @PreAuthorize("hasRole('ADMIN')")} on {@code UsersController}
 *   needs the JWT's {@code role} claim mapped to a {@code ROLE_*} granted
 *   authority — the shared chain's default {@link JwtAuthenticationConverter}
 *   reads the OAuth2 {@code scope}/{@code scp} claim instead, which this
 *   module's tokens do not carry.</li>
 * </ol>
 *
 * <p>Reuses the SAME {@link JwtDecoder} and {@code HandlerExceptionResolver}
 * beans {@code :shared:security}'s chain uses (both plain framework types —
 * consuming them by type is not a reference into that module's internal
 * packages) and the identical entry-point/access-denied-handler delegation
 * idiom, so 401/403 render through the exact same {@code
 * application/problem+json} contract as every other error.
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
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/login").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(handling -> handling
                        .accessDeniedHandler((request, response, ex) ->
                                exceptionResolver.resolveException(request, response, null, ex)))
                .oauth2ResourceServer(oauth2 -> oauth2
                        .authenticationEntryPoint(entryPoint)
                        .jwt(jwt -> jwt.decoder(jwtDecoder).jwtAuthenticationConverter(roleAwareConverter())));
        return http.build();
    }

    private static JwtAuthenticationConverter roleAwareConverter() {
        JwtGrantedAuthoritiesConverter authorities = new JwtGrantedAuthoritiesConverter();
        authorities.setAuthorityPrefix("ROLE_");
        authorities.setAuthoritiesClaimName(JwtClaims.ROLE);

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(authorities);
        return converter;
    }
}
