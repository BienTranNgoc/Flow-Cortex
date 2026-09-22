-- =============================================================================
-- Flow-Cortex - Spring Modulith Event Publication Registry (V2026.09.22.003)
--
-- Backs the persistent Event Publication Registry the async after-commit run
-- execution model depends on (design.md D5/R-050): the `@ApplicationModuleListener`
-- that drives a workflow run's steps needs delivery-loss protection (a
-- crash/restart between commit and listener completion must redeliver the
-- event, not lose the run) — spring-modulith-starter-jpa provides that, but
-- (unlike the JDBC event-publication backend) does NOT auto-create its own
-- schema, so it must be migrated like any other Flyway-owned table
-- (design.md D7/R-004, `ddl-auto: validate`).
--
-- Column shape matches Spring Modulith 2.1's `DefaultJpaEventPublication`
-- JPA entity exactly (verified against the shipped
-- spring-modulith-events-jpa-2.1.0.jar and the officially published
-- PostgreSQL "current schema" DDL, docs.spring.io/spring-modulith/reference/2.1/appendix.html#schemas.postgresql)
-- — TEXT rather than a bounded VARCHAR for listener_id/event_type/status since
-- Modulith puts no length cap on these (a truncated listener id or serialized
-- event would silently corrupt the registry).
-- =============================================================================

CREATE TABLE event_publication (
    id                     uuid        PRIMARY KEY,
    listener_id            text        NOT NULL,
    event_type             text        NOT NULL,
    serialized_event       text        NOT NULL,
    publication_date       timestamptz NOT NULL,
    completion_date        timestamptz,
    status                 text,
    completion_attempts    integer,
    last_resubmission_date timestamptz
);

-- Mirrors the officially published indexes: a hash index for the
-- by-event-content idempotency lookups the registry performs, and a
-- completion-date index for the "find incomplete" recovery query run on
-- restart.
CREATE INDEX idx_event_publication_serialized_event ON event_publication USING hash (serialized_event);
CREATE INDEX idx_event_publication_completion_date ON event_publication (completion_date);
