package com.flowcortex.shared.ratelimit;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.BucketConfiguration;
import io.github.bucket4j.ConsumptionProbe;
import io.github.bucket4j.EstimationProbe;
import io.github.bucket4j.TimeMeter;
import io.github.bucket4j.distributed.ExpirationAfterWriteStrategy;
import io.github.bucket4j.distributed.jdbc.PrimaryKeyMapper;
import io.github.bucket4j.postgresql.Bucket4jPostgreSQL;
import io.github.bucket4j.postgresql.PostgreSQLSelectForUpdateBasedProxyManager;
import java.time.Duration;
import java.util.Optional;
import javax.sql.DataSource;

/**
 * Token buckets in the {@code rate_limit_bucket} table, so a budget is shared by every backend
 * instance on the same database. A bucket that has refilled to capacity expires and is removed by
 * {@link #removeExpired(int)}.
 */
public class PostgresRateLimitStore implements RateLimitStore {

    private final PostgreSQLSelectForUpdateBasedProxyManager<String> buckets;

    public PostgresRateLimitStore(DataSource dataSource) {
        this(dataSource, null);
    }

    /** {@code clientClock} drives refills and expiry; {@code null} keeps Bucket4j's wall-clock default. */
    public PostgresRateLimitStore(DataSource dataSource, TimeMeter clientClock) {
        Bucket4jPostgreSQL.PostgreSQLSelectForUpdateBasedProxyManagerBuilder<String> builder =
                Bucket4jPostgreSQL.selectForUpdateBasedBuilder(dataSource)
                        .primaryKeyMapper(PrimaryKeyMapper.STRING)
                        .table("rate_limit_bucket")
                        .expirationAfterWrite(
                                ExpirationAfterWriteStrategy.basedOnTimeForRefillingBucketUpToMax(Duration.ZERO));
        if (clientClock != null) {
            builder.clientClock(clientClock);
        }
        this.buckets = builder.build();
    }

    @Override
    public Optional<Duration> tryConsume(String key, long capacity, Duration period) {
        ConsumptionProbe probe = bucket(key, capacity, period).tryConsumeAndReturnRemaining(1);
        return probe.isConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    @Override
    public Optional<Duration> estimate(String key, long capacity, Duration period) {
        EstimationProbe probe = bucket(key, capacity, period).estimateAbilityToConsume(1);
        return probe.canBeConsumed() ? Optional.empty() : Optional.of(Duration.ofNanos(probe.getNanosToWaitForRefill()));
    }

    @Override
    public void consume(String key, long capacity, Duration period) {
        bucket(key, capacity, period).tryConsume(1);
    }

    /** Deletes up to {@code batchSize} buckets already past their expiry and returns how many went. */
    public int removeExpired(int batchSize) {
        return buckets.removeExpired(batchSize);
    }

    private Bucket bucket(String key, long capacity, Duration period) {
        return buckets.builder().build(key, () -> BucketConfiguration.builder()
                .addLimit(limit -> limit.capacity(capacity).refillGreedy(capacity, period))
                .build());
    }
}
