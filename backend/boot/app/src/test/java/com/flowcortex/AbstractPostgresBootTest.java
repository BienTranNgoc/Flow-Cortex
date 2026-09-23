package com.flowcortex;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres wiring for :boot:app tests that boot the real application
 * context — no H2; every DB-backed test uses Testcontainers Postgres via Boot's {@code
 * @ServiceConnection}.
 *
 * <p>Static field on this shared base so every subclass reuses the same instance — one
 * container for the whole {@code :boot:app} test run, not one per class. Public (field
 * protected) so subclasses outside this package can extend it.
 *
 * <p>Started via a plain static initializer, deliberately WITHOUT {@code @Container}/{@code
 * @Testcontainers}: JUnit's extension stops an inherited {@code @Container} field at the end of
 * the OWNING class's lifecycle, which races a mid-suite restart against a Spring context cached
 * from an earlier class — still holding a Hikari pool bound to the old, stale port (surfaced
 * once a third {@code @SpringBootTest} class joined the suite). {@code @ServiceConnection} only
 * needs the field + annotation, not the lifecycle extension — so this is start-once, stop-never
 * (Ryuk reaps it when the JVM exits).
 */
public abstract class AbstractPostgresBootTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
