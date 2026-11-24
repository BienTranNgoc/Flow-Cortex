package com.flowcortex.user.oauth;

import com.flowcortex.user.application.RateLimit;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Binds {@code flowcortex.auth.mcp-oauth.*} ({@code application.yml} carries the defaults and env
 * names) and combines it with the settings it depends on. Beans rather than scanned classes, so a
 * controller slice test does not pull the switch in without its dependencies.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpOAuthConfig.Settings.class)
class McpOAuthConfig {

    @Validated
    @ConfigurationProperties("flowcortex.auth.mcp-oauth")
    record Settings(boolean enabled, @NotNull @Valid RateLimit registerPerIp, @NotNull @Valid RateLimit tokenPerIp,
            @NotNull Duration retentionInterval) {
    }

    @Bean
    McpOAuthProperties mcpOAuthProperties(Settings settings,
            @Value("${flowcortex.auth.personal-access-tokens.enabled:false}") boolean personalAccessTokensEnabled,
            @Value("${flowcortex.app.public-url:}") String publicUrl) {
        return new McpOAuthProperties(settings.enabled(), personalAccessTokensEnabled, publicUrl,
                settings.registerPerIp(), settings.tokenPerIp(), settings.retentionInterval());
    }

    @Bean
    McpOAuthSwitch mcpOAuthSwitch(McpOAuthProperties properties) {
        return new McpOAuthSwitch(properties);
    }
}
