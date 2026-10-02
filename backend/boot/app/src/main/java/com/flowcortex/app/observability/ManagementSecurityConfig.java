package com.flowcortex.app.observability;

import org.springframework.boot.actuate.autoconfigure.web.server.ManagementPortType;
import org.springframework.boot.security.autoconfigure.actuate.web.servlet.EndpointRequest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Health, info and metrics answer anonymously on the management port, which only the service
 * network reaches. The application's own chains apply to that port as well, so this one is
 * ordered after the user chain (1) and the admin chain (2) and before the shared catch-all. With
 * the management port separate, {@link EndpointRequest} matches nothing on the API port; startup
 * fails when it is not.
 */
@Configuration(proxyBeanMethods = false)
class ManagementSecurityConfig {

    ManagementSecurityConfig(Environment environment) {
        if (ManagementPortType.get(environment) == ManagementPortType.SAME) {
            throw new IllegalStateException("management.server.port must be set and differ from server.port: "
                    + "on the API port this chain would serve health and metrics to anyone");
        }
    }

    @Bean
    @Order(3)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                .securityMatcher(EndpointRequest.to("health", "info", "prometheus"))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
