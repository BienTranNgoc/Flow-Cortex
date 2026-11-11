package com.flowcortex.billing.webhook;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Provider callbacks arrive from the provider's servers with no token, so the webhook routes are open to
 * authentication and guarded by the source address allowlist inside the controller. Sorts ahead of the shared
 * chain, which would otherwise answer 401.
 */
@Configuration(proxyBeanMethods = false)
class BillingWebhookSecurityConfig {

    @Bean
    @Order(7)
    SecurityFilterChain billingWebhookSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher("/api/v1/billing/webhooks/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
