-- Rate card: every credit price the backend quotes or charges, and the mapping from a step type
-- or media capability to the price that covers it.
--
-- No foreign key from capability_pricing to any step table: step types are code, not rows, and
-- capability_pricing.capability is a step type or a media capability value. The ref a mapping
-- names is checked by the application at startup (coverage), not by a constraint, because several
-- rows of one ref exist over time. rate_card_ref and capability allow upper case because
-- APPROVAL_CHECKPOINT is a step type.
--
-- To change a price: UPDATE the key's open row SET effective_to = T; INSERT the new row with
-- effective_from = T, open-ended, in the same migration. A bare INSERT of a second open row is
-- refused by uq_rate_card_key_open; a closed row with no successor ends coverage and stops startup.
--
-- Rollback: DROP TABLE capability_pricing; DROP TABLE rate_card; together with reverting the code
-- (the flowcortex.credits.step-costs property map comes back with it).

CREATE TABLE rate_card (
    id               uuid          PRIMARY KEY,
    rate_card_ref    text          NOT NULL CHECK (rate_card_ref ~ '^[A-Za-z0-9._-]{1,64}$'),
    pricing_unit     text          NOT NULL CHECK (pricing_unit IN (
        'per_request', 'per_onboarding', 'per_image', 'per_1k_chars', 'per_second_video', 'per_minute',
        'per_minute_audio', 'per_megapixel', 'per_megapixel_second', 'per_video', 'per_1m_tokens_in',
        'per_1m_tokens_out')),
    provider_key     text          NOT NULL CHECK (provider_key ~ '^[a-z0-9._-]{1,64}$'),
    model_key        text          NULL CHECK (model_key IS NULL OR length(model_key) BETWEEN 1 AND 128),
    dimensions       jsonb         NULL CHECK (dimensions IS NULL
        OR (jsonb_typeof(dimensions) = 'object' AND dimensions <> '{}'::jsonb)),
    credits_per_unit numeric(14,6) NOT NULL CHECK (credits_per_unit >= 0),
    markup_pct       numeric(6,2)  NOT NULL DEFAULT 0 CHECK (markup_pct >= 0),
    effective_from   timestamptz   NOT NULL,
    effective_to     timestamptz   NULL,
    created_at       timestamptz   NOT NULL DEFAULT now(),
    CHECK (effective_to IS NULL OR effective_to > effective_from)
);

-- A key is (rate_card_ref, pricing_unit, provider_key, model_key or '', dimensions or '{}'); an
-- empty-object dimensions value is refused above so the COALESCE never collides with "none".
CREATE UNIQUE INDEX uq_rate_card_key_from ON rate_card
    (rate_card_ref, pricing_unit, provider_key, COALESCE(model_key, ''), COALESCE(dimensions, '{}'::jsonb),
     effective_from);

CREATE UNIQUE INDEX uq_rate_card_key_open ON rate_card
    (rate_card_ref, pricing_unit, provider_key, COALESCE(model_key, ''), COALESCE(dimensions, '{}'::jsonb))
    WHERE effective_to IS NULL;

CREATE INDEX idx_rate_card_ref ON rate_card (rate_card_ref);

CREATE TABLE capability_pricing (
    capability      text   PRIMARY KEY CHECK (capability ~ '^[A-Za-z0-9._-]{1,64}$'),
    estimator_kind  text   NOT NULL CHECK (estimator_kind IN ('rate_card', 'free', 'mc')),
    rate_card_ref   text   NULL,
    pricing_unit    text   NULL CHECK (pricing_unit IS NULL OR pricing_unit IN (
        'per_request', 'per_onboarding', 'per_image', 'per_1k_chars', 'per_second_video', 'per_minute',
        'per_minute_audio', 'per_megapixel', 'per_megapixel_second', 'per_video', 'per_1m_tokens_in',
        'per_1m_tokens_out')),
    provider_key    text   NULL,
    quantity_params text[] NOT NULL DEFAULT '{}',
    CHECK ((estimator_kind = 'rate_card'
            AND rate_card_ref IS NOT NULL AND pricing_unit IS NOT NULL AND provider_key IS NOT NULL)
        OR (estimator_kind <> 'rate_card'
            AND rate_card_ref IS NULL AND pricing_unit IS NULL AND provider_key IS NULL))
);

-- Seed: the flat step prices that lived in flowcortex.credits.step-costs, one per-request row each.
INSERT INTO rate_card (id, rate_card_ref, pricing_unit, provider_key, credits_per_unit, markup_pct, effective_from)
SELECT gen_random_uuid(), p.ref, 'per_request', 'flowcortex', p.credits, 0, TIMESTAMPTZ '2026-10-18 00:00:00+00'
FROM (VALUES
    ('echo', 1),
    ('fail', 20),
    ('probe', 10),
    ('extract_audio', 50),
    ('align_audio', 50),
    ('generate_script', 100),
    ('generate_speech', 100),
    ('generate_storyboard', 100),
    ('generate_video', 200),
    ('render_video', 200),
    ('translate', 100),
    ('tts', 100)
) AS p (ref, credits);

INSERT INTO capability_pricing (capability, estimator_kind, rate_card_ref, pricing_unit, provider_key)
SELECT p.ref, 'rate_card', p.ref, 'per_request', 'flowcortex'
FROM (VALUES
    ('echo'), ('fail'), ('probe'), ('extract_audio'), ('align_audio'), ('generate_script'),
    ('generate_speech'), ('generate_storyboard'), ('generate_video'), ('render_video'),
    ('translate'), ('tts')
) AS p (ref);

INSERT INTO capability_pricing (capability, estimator_kind) VALUES ('APPROVAL_CHECKPOINT', 'free');
