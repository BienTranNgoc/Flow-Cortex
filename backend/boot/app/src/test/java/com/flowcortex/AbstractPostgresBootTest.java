package com.flowcortex;

import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Shared Testcontainers Postgres wiring for :boot:app tests that boot the
 * real application context (design.md D7 — no H2; every DB-backed test uses
 * Testcontainers Postgres via Boot's {@code @ServiceConnection}).
 *
 * <p>The container field is static and declared on this shared base so every
 * subclass reuses the very same instance — one container started for the
 * whole {@code :boot:app} test run, not one per test class. Public (and the
 * field protected) so a subclass outside this package — e.g. {@code
 * com.flowcortex.app.security}/{@code com.flowcortex.app.openapi} — can
 * still extend it.
 *
 * <p>Started via a plain static initializer, deliberately WITHOUT {@code
 * @Container}/{@code @Testcontainers} (task 3.1 finding): JUnit's extension
 * stops a {@code @Container} field at the end of the OWNING test class's
 * lifecycle even when the field is inherited from this shared base, which
 * races a mid-suite restart (a new container on a new port) against a
 * Spring context — cached from an earlier class with a different {@code
 * @SpringBootTest} configuration — that still holds a Hikari pool bound to
 * the old, now-stale port. This surfaced only once a THIRD {@code
 * @SpringBootTest} class (OpenApiSnapshotTest) joined the two from slice 2.
 * {@code @ServiceConnection} only needs the field + annotation to wire the
 * datasource — it does not require JUnit's container-lifecycle extension —
 * so this is start-once, stop-never (Testcontainers' Ryuk reaper cleans up
 * when the test JVM exits), which is genuinely one container for the whole
 * test run instead of one per test class.
 */
public abstract class AbstractPostgresBootTest {

    @ServiceConnection
    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine");

    static {
        POSTGRES.start();
    }
}
