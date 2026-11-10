package com.flowcortex.app.ratelimit;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;

/**
 * The limiter store's dedicated connection pool. Held in a holder rather than declared as a
 * {@code DataSource} bean so Spring Boot's {@code DataSource}, Flyway and JPA keep the primary pool.
 */
final class RateLimitStorePool implements AutoCloseable {

    private final HikariDataSource dataSource;

    private RateLimitStorePool(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** Opens lazily: the first connection is made by the first limited call, not at startup. */
    static RateLimitStorePool open(
            String jdbcUrl, String username, String password, RateLimitStoreProperties properties) {
        HikariConfig config = new HikariConfig();
        config.setPoolName("rate-limit-store");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(properties.maxPoolSize());
        config.setMinimumIdle(1);
        config.setConnectionTimeout(properties.connectionTimeout().toMillis());
        config.setInitializationFailTimeout(-1);
        config.setConnectionInitSql("SET lock_timeout = " + properties.lockTimeout().toMillis());
        return new RateLimitStorePool(new HikariDataSource(config));
    }

    DataSource dataSource() {
        return dataSource;
    }

    @Override
    public void close() {
        dataSource.close();
    }
}
