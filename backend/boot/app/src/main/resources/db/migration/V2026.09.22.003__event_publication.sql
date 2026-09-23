-- Spring Modulith Event Publication Registry. The after-commit
-- `@ApplicationModuleListener` that drives a run's steps needs delivery-loss
-- protection (a crash between commit and listener completion must redeliver,
-- not lose the run). spring-modulith-starter-jpa provides that but, unlike
-- the JDBC backend, does not create its own schema, so it is Flyway-owned here.
--
-- Column shape matches Modulith 2.1's `DefaultJpaEventPublication` entity and
-- the published PostgreSQL DDL
-- (docs.spring.io/spring-modulith/reference/2.1/appendix.html#schemas.postgresql).
-- TEXT rather than bounded VARCHAR for listener_id/event_type/status: Modulith
-- caps no length, and truncation would silently corrupt the registry.

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
