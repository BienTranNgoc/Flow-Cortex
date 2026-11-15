package com.flowcortex.app.ratelimit;

import com.flowcortex.shared.ratelimit.PostgresRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiters;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcConnectionDetails;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the rate-limit registry over the Postgres store and its dedicated pool. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RateLimitStoreProperties.class)
class RateLimitConfig {

    /** Same database and credentials as the application pool, taken from the connection details Boot itself uses. */
    @Bean
    RateLimitStorePool rateLimitStorePool(JdbcConnectionDetails connection, RateLimitStoreProperties properties) {
        return RateLimitStorePool.open(
                connection.getJdbcUrl(), connection.getUsername(), connection.getPassword(), properties);
    }

    @Bean
    PostgresRateLimitStore rateLimitStore(RateLimitStorePool pool) {
        return new PostgresRateLimitStore(pool.dataSource());
    }

    @Bean
    RateLimitTelemetry rateLimitTelemetry(MeterRegistry meters, Clock clock) {
        return new RateLimitTelemetry(meters, clock);
    }

    @Bean
    RateLimiters rateLimiters(
            ObjectProvider<RateLimitDefinition> definitions, PostgresRateLimitStore store, RateLimitTelemetry telemetry) {
        return new RateLimiters(definitions.orderedStream().toList(), store, telemetry);
    }
}
