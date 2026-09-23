-- Deliberately missing the `label` column that
-- probetest.ProbeEntity maps, so ddl-auto: validate fails closed on schema
-- drift instead of silently mutating the table to match.
CREATE TABLE probe_entity (
    id uuid PRIMARY KEY
);
