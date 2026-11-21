package com.flowcortex.mail.web;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * The email-link routes answer without a token: the signed link in the mail is the credential. The chain
 * matches only {@code /api/v1/email-links/**}, so no other chain's routes change.
 */
@Configuration(proxyBeanMethods = false)
class EmailLinksSecurityConfig {

    @Bean
    @Order(9)
    SecurityFilterChain emailLinksSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/email-links/**")
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
