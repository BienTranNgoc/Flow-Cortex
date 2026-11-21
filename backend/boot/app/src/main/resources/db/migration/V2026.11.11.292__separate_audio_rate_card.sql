-- Prices the separate_audio capability: 50 credits per request, like extract_audio and align_audio.
-- Without this row the startup coverage check refuses to boot once the capability is registered.
--
-- To change the price: UPDATE the open row SET effective_to = T; INSERT the new row with
-- effective_from = T, open-ended, in the same migration (see V2026.11.10.100__rate_card.sql).
--
-- Rollback (revert the code too; run in one transaction, and include the history delete so a later
-- roll-forward does not skip this version):
--   BEGIN;
--   DELETE FROM capability_pricing WHERE capability = 'separate_audio';
--   DELETE FROM rate_card WHERE rate_card_ref = 'audio.separate';
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.11.230';
--   COMMIT;

INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key, credits_per_unit, markup_pct, effective_from)
VALUES (gen_random_uuid(), 'audio.separate', 'per_request', 'flowcortex', 50, 0,
        TIMESTAMPTZ '2026-11-10 00:00:00+00');

INSERT INTO capability_pricing (capability, estimator_kind, rate_card_ref, pricing_unit, provider_key)
VALUES ('separate_audio', 'rate_card', 'audio.separate', 'per_request', 'flowcortex');
