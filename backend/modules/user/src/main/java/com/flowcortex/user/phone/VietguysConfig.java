package com.flowcortex.user.phone;

import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The VietGuys sender and everything it needs, present only with {@code flowcortex.sms.sender=vietguys}.
 * Every required value is checked when its bean is built, so an incomplete setup fails startup naming
 * the property and never its value.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "flowcortex.sms.sender", havingValue = "vietguys")
@EnableConfigurationProperties(VietguysProperties.class)
class VietguysConfig {

    private static final String PREFIX = "flowcortex.sms.vietguys.";

    @Bean
    SmsTemplates vietguysTemplates(VietguysProperties properties) {
        VietguysProperties.Templates templates = properties.templates();
        if (templates == null) {
            throw new IllegalStateException(PREFIX + "templates.otp is not set");
        }
        return SmsTemplates.validated(templates.otp(), templates.phoneChanged(), templates.phoneChangeReverted());
    }

    @Bean
    CredentialSealer vietguysCredentialSealer(VietguysProperties properties) {
        Map<String, String> ring = new LinkedHashMap<>(properties.keys());
        // An unset spare key (the rotation slot) is not a key; an unset current key is, and fails by name.
        ring.entrySet().removeIf(entry -> !entry.getKey().equals(properties.currentKeyId())
                && (entry.getValue() == null || entry.getValue().isBlank()));
        return new CredentialSealer(ring, properties.currentKeyId());
    }

    @Bean
    VietguysHttp vietguysHttp(VietguysProperties properties) {
        return new VietguysHttp(
                positive(properties.connectTimeout(), "connect-timeout"),
                positive(properties.readTimeout(), "read-timeout"));
    }

    @Bean
    SmsCarrierTelemetry smsCarrierTelemetry(MeterRegistry meters) {
        return new SmsCarrierTelemetry(meters);
    }

    @Bean
    VietguysTokenStore vietguysTokenStore(VietguysProperties properties, JdbcTemplate jdbc,
            PlatformTransactionManager transactions, VietguysHttp http, CredentialSealer sealer, Clock clock,
            SmsCarrierTelemetry telemetry) {
        return new VietguysTokenStore(jdbc, transactions, http, sealer,
                url(properties.tokenUrl(), "token-url"),
                required(properties.username(), "username"),
                required(properties.refreshToken(), "refresh-token"),
                clock, telemetry);
    }

    @Bean
    VietguysSmsSender vietguysSmsSender(VietguysProperties properties, VietguysHttp http, VietguysTokenStore tokens,
            SmsTemplates templates, SmsCarrierTelemetry telemetry) {
        return new VietguysSmsSender(http, tokens, templates,
                url(properties.smsUrl(), "sms-url"),
                required(properties.username(), "username"),
                required(properties.brandname(), "brandname"),
                telemetry);
    }

    @Bean
    VietguysTokenKeepalive vietguysTokenKeepalive(VietguysTokenStore tokens) {
        return new VietguysTokenKeepalive(tokens);
    }

    @Bean
    CarrierCredentialStatus vietguysCredentialStatus(VietguysTokenStore tokens) {
        return tokens::credentialState;
    }

    private static String required(String value, String property) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(PREFIX + property + " is not set");
        }
        return value;
    }

    private static URI url(URI value, String property) {
        if (value == null || value.getHost() == null
                || !("https".equalsIgnoreCase(value.getScheme()) || "http".equalsIgnoreCase(value.getScheme()))) {
            throw new IllegalStateException(PREFIX + property + " must be an http(s) URL with a host");
        }
        return value;
    }

    private static Duration positive(Duration value, String property) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalStateException(PREFIX + property + " must be a positive duration");
        }
        return value;
    }
}
