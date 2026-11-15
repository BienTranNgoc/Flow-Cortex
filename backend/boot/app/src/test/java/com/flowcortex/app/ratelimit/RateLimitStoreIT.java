package com.flowcortex.app.ratelimit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.shared.ratelimit.PostgresRateLimitStore;
import com.flowcortex.shared.ratelimit.RateLimitDefinition;
import com.flowcortex.shared.ratelimit.RateLimitKeys;
import com.flowcortex.shared.ratelimit.RateLimitTelemetry;
import com.flowcortex.shared.ratelimit.RateLimiter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/** The Postgres store on its own pools: shared budgets, expiry and the sweep, and failing open under pool exhaustion. */
class RateLimitStoreIT extends AbstractPostgresBootTest {

    private static final RateLimitDefinition FIVE_PER_HOUR = new RateLimitDefinition("store-it", 5, Duration.ofHours(1));

    private final List<RateLimitStorePool> pools = new ArrayList<>();

    @BeforeAll
    static void migrate() {
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
    }

    @AfterEach
    void closePools() {
        pools.forEach(RateLimitStorePool::close);
        pools.clear();
    }

    private RateLimitStorePool openPool(int maxPoolSize) {
        RateLimitStoreProperties properties =
                new RateLimitStoreProperties(maxPoolSize, Duration.ofMillis(250), Duration.ofMillis(200),
                        Duration.ofMillis(300));
        RateLimitStorePool pool = RateLimitStorePool.open(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword(), properties);
        pools.add(pool);
        return pool;
    }

    private static Connection plainConnection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static long rowsFor(String key) throws SQLException {
        try (Connection connection = plainConnection(); Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT count(*) FROM rate_limit_bucket WHERE id = '" + key + "'")) {
            rows.next();
            return rows.getLong(1);
        }
    }

    @Test
    void twoInstancesSpendOneBudget() {
        PostgresRateLimitStore first = new PostgresRateLimitStore(openPool(2).dataSource());
        PostgresRateLimitStore second = new PostgresRateLimitStore(openPool(2).dataSource());
        String key = RateLimitKeys.storageKey(FIVE_PER_HOUR, "203.0.113.7");

        int admitted = 0;
        for (int i = 0; i < 3; i++) {
            admitted += first.tryConsume(key, 5, Duration.ofHours(1)).isEmpty() ? 1 : 0;
        }
        for (int i = 0; i < 3; i++) {
            Optional<Duration> wait = second.tryConsume(key, 5, Duration.ofHours(1));
            admitted += wait.isEmpty() ? 1 : 0;
            if (i == 2) {
                assertThat(wait).isPresent();
                assertThat(wait.get()).isPositive();
            }
        }

        assertThat(admitted).isEqualTo(5);
    }

    @Test
    void storedIdsHoldTheLimiterNameAndADigestOnly() throws SQLException {
        PostgresRateLimitStore store = new PostgresRateLimitStore(openPool(2).dataSource());
        store.tryConsume(RateLimitKeys.storageKey(FIVE_PER_HOUR, "an@example.com"), 5, Duration.ofHours(1));

        try (Connection connection = plainConnection(); Statement statement = connection.createStatement();
                ResultSet ids = statement.executeQuery("SELECT id FROM rate_limit_bucket")) {
            int seen = 0;
            while (ids.next()) {
                seen++;
                assertThat(ids.getString(1)).matches("^[a-z][a-z0-9-]*:[0-9a-f]{64}$");
            }
            assertThat(seen).isEqualTo(1);
        }
    }

    @Test
    void aRefilledBucketIsSweptAndTheNextCallStartsFull() throws Exception {
        Duration period = Duration.ofSeconds(1);
        RateLimitDefinition twoPerSecond = new RateLimitDefinition("store-it-sweep", 2, period);
        PostgresRateLimitStore store = new PostgresRateLimitStore(openPool(2).dataSource());
        String key = RateLimitKeys.storageKey(twoPerSecond, "203.0.113.9");
        assertThat(store.tryConsume(key, 2, period)).isEmpty();
        assertThat(rowsFor(key)).isEqualTo(1);

        Thread.sleep(2_000);
        assertThat(store.removeExpired(1000)).isGreaterThanOrEqualTo(1);

        assertThat(rowsFor(key)).isZero();
        assertThat(store.tryConsume(key, 2, period)).isEmpty();
        assertThat(store.tryConsume(key, 2, period)).isEmpty();
        assertThat(store.tryConsume(key, 2, period)).isPresent();
    }

    @Test
    void aStatementThatStallsAfterConnectingIsCancelledByTheServer() throws SQLException {
        RateLimitStorePool pool = openPool(2);
        try (Connection connection = pool.dataSource().getConnection(); Statement statement = connection.createStatement()) {
            try (ResultSet shown = statement.executeQuery("SHOW statement_timeout")) {
                shown.next();
                assertThat(shown.getString(1)).isEqualTo("300ms");
            }
            long started = System.nanoTime();
            assertThatThrownBy(() -> statement.execute("SELECT pg_sleep(5)"))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("statement timeout");
            assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        }

        RateLimiter limiter = new RateLimiter(FIVE_PER_HOUR, new PostgresRateLimitStore(pool.dataSource()),
                new RateLimitTelemetry(new SimpleMeterRegistry(), Clock.systemUTC()));
        assertThat(limiter.tryAcquire("203.0.113.8")).isEmpty();
    }

    @Test
    void aLimiterWhoseConnectionsAreAllHeldAdmitsQuicklyAndCountsTheFailure() throws SQLException {
        RateLimitStorePool pool = openPool(2);
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        RateLimiter limiter = new RateLimiter(FIVE_PER_HOUR, new PostgresRateLimitStore(pool.dataSource()),
                new RateLimitTelemetry(meters, Clock.systemUTC()));

        try (Connection heldOne = pool.dataSource().getConnection(); Connection heldTwo = pool.dataSource().getConnection()) {
            long started = System.nanoTime();
            Optional<Duration> answer = limiter.tryAcquire("203.0.113.7");
            Duration took = Duration.ofNanos(System.nanoTime() - started);

            assertThat(answer).isEmpty();
            assertThat(took).isLessThan(Duration.ofSeconds(1));
            assertThat(heldOne).isNotSameAs(heldTwo);
        }

        assertThat(meters.get("rate_limit.store.failures").tag("limiter", "store-it").counter().count()).isEqualTo(1.0);
        assertThatCode(() -> limiter.tryAcquire("203.0.113.7")).doesNotThrowAnyException();
    }
}
