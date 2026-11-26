package com.flowcortex.billing.catalog;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;
import org.springframework.security.web.util.matcher.OrRequestMatcher;

/**
 * The two public catalogue GETs answer anonymously; they are matched before the shared catch-all, which would
 * require a token. No resource server is configured, so a bearer token is never decoded here: the answer is the
 * same with or without one, and user-keyed gates (such as the phone gate) never fire on these paths. Any other
 * method on the paths falls through to the shared chain.
 */
@Configuration(proxyBeanMethods = false)
class CatalogueSecurityConfig {

    @Bean
    @Order(11)
    SecurityFilterChain catalogueSecurityFilterChain(HttpSecurity http) throws Exception {
        PathPatternRequestMatcher.Builder paths = PathPatternRequestMatcher.withDefaults();
        http
                .securityMatcher(new OrRequestMatcher(
                        paths.matcher(HttpMethod.GET, "/api/v1/billing/plans"),
                        paths.matcher(HttpMethod.GET, "/api/v1/billing/topup-packages")))
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        return http.build();
    }
}
