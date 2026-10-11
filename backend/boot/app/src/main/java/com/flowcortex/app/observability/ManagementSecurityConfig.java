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

    /**
     * Why two matcher groups: the management-port matcher covers health/info/prometheus
     * served on port 8081; the literal-path matcher covers the {@code /livez} and
     * {@code /readyz} probes that Boot 4 also registers on the API port when
     * {@code management.endpoint.health.probes.add-additional-paths=true}. Without
     * the literal matcher, Boot 3.4+ default-applies its own auth requirement on
     * those probe URLs on the API port — anonymous curl gets 401, the compose
     * healthcheck marks the container unhealthy, and {@code /readyz} never returns
     * 200 even with the shared chain's permitAll. Match the probes by literal
     * path here so this chain (the one ordered before the catch-all) wins.
     */
    @Bean
    @Order(3)
    SecurityFilterChain managementSecurityFilterChain(HttpSecurity http) throws Exception {
        org.springframework.security.web.util.matcher.RequestMatcher probes =
                request -> {
                    String path = request.getRequestURI();
                    return "/livez".equals(path) || "/readyz".equals(path);
                };
        org.springframework.security.web.util.matcher.RequestMatcher endpoints =
                EndpointRequest.to("health", "info", "prometheus");
        http
                .securityMatcher(new org.springframework.security.web.util.matcher.OrRequestMatcher(endpoints, probes))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
