-- Task 2.3 IT — matches probetest.ProbeEntity's mapping exactly, so
-- ddl-auto: validate passes against it.
CREATE TABLE probe_entity (
    id    uuid PRIMARY KEY,
    label varchar(255) NOT NULL
);
