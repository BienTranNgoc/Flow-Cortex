-- Prices for the link and ingest step types: fetching a link and copying an upload into a run
-- cost nothing themselves, so each is a free capability_pricing row with no quantity parameters.
-- The startup coverage check stops the backend when a registered step type has no row, so these
-- rows ship with the code that registers the types.
--
-- Rollback (revert the code too: the coverage check stops startup if the types stay registered
-- without rows).
--   DELETE FROM capability_pricing WHERE capability IN ('fetch_video', 'ingest_video', 'ingest_image');

INSERT INTO capability_pricing (capability, estimator_kind) VALUES
    ('fetch_video', 'free'), ('ingest_video', 'free'), ('ingest_image', 'free');
