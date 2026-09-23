package probetest;

import static org.assertj.core.api.Assertions.assertThat;

import org.hibernate.tool.schema.spi.SchemaManagementException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Proves {@code ddl-auto: validate} both ways on real Postgres: boot
 * succeeds when the schema Flyway migrated matches
 * {@link ProbeEntity}'s mapping, and boot fails when it deliberately doesn't.
 * Uses {@link ApplicationContextRunner} (rather than a full
 * {@code @SpringBootTest}) so the failing scenario can be asserted on
 * directly instead of surfacing as a raw JUnit setup error, and so both
 * directions run against exactly the auto-configuration this proof needs —
 * nothing from the real app's own component scan leaks in.
 *
 * <p>Two separate containers, one per scenario: both migrations create the
 * same {@code probe_entity} table name, so sharing one database across the
 * two directions would collide.
 */
class PersistenceValidationTest {

    private static final PostgreSQLContainer<?> MATCHING_SCHEMA_DB = new PostgreSQLContainer<>("postgres:17-alpine");
    private static final PostgreSQLContainer<?> MISMATCHED_SCHEMA_DB =
            new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        MATCHING_SCHEMA_DB.start();
        MISMATCHED_SCHEMA_DB.start();
    }

    @AfterAll
    static void stopContainers() {
        MATCHING_SCHEMA_DB.stop();
        MISMATCHED_SCHEMA_DB.stop();
    }

    @Test
    void bootSucceedsWhenTheMigratedSchemaMatchesTheMapping() {
        contextRunner(MATCHING_SCHEMA_DB, "classpath:db/it-migration-match")
                .run(context -> assertThat(context).hasNotFailed());
    }

    @Test
    void bootFailsWhenTheMigratedSchemaIsMissingAMappedColumn() {
        contextRunner(MISMATCHED_SCHEMA_DB, "classpath:db/it-migration-mismatch")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure()).hasRootCauseInstanceOf(SchemaManagementException.class);
                });
    }

    private static ApplicationContextRunner contextRunner(PostgreSQLContainer<?> postgres, String flywayLocation) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        HibernateJpaAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(ProbeEntityScanConfig.class)
                .withPropertyValues(
                        "spring.datasource.url=" + postgres.getJdbcUrl(),
                        "spring.datasource.username=" + postgres.getUsername(),
                        "spring.datasource.password=" + postgres.getPassword(),
                        "spring.jpa.hibernate.ddl-auto=validate",
                        "spring.flyway.locations=" + flywayLocation);
    }

    @Configuration
    @EntityScan(basePackageClasses = ProbeEntity.class)
    static class ProbeEntityScanConfig {
    }
}
