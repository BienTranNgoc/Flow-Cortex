package com.flowcortex.support.web;

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
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * Who may call the Help Center routes. Articles, the settings and the guest assistant answer
 * without a token; everything else needs one. Whether a signed-in caller may use an admin route is
 * decided per handler by the admin authorization interceptor, which reads the caller's keys on each
 * request. The admin chain ({@code /api/v1/admin/**}, order 2) matches the admin routes first.
 */
@Configuration(proxyBeanMethods = false)
class SupportSecurityConfig {

    @Bean
    @Order(5)
    SecurityFilterChain supportSecurityFilterChain(HttpSecurity http, JwtDecoder jwtDecoder,
            @Qualifier("handlerExceptionResolver") HandlerExceptionResolver exceptionResolver) throws Exception {
        AuthenticationEntryPoint entryPoint = (request, response, authException) ->
                exceptionResolver.resolveException(request, response, null, authException);

        http
                .securityMatcher("/api/v1/support/**", "/api/v1/admin/support/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/api/v1/support/kb/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/support/settings").permitAll()
                        .requestMatchers("/api/v1/support/assistant/guest/**").permitAll()
                        .requestMatchers("/api/v1/admin/support/**").authenticated()
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
