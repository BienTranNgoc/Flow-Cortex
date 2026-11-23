-- Additive: creates the singleton onboarding sample and its per-language input translations, and
-- inserts the one empty singleton row; no existing row is touched. Version block 120-129.
-- No FK to workflow: modules reach each other only through public ports.
CREATE TABLE onboarding_sample (
    id            smallint    PRIMARY KEY CHECK (id = 1),
    workflow_id   uuid,
    sample_input  text,
    source_locale varchar(2),
    updated_by    uuid,
    updated_at    timestamptz NOT NULL,
    CONSTRAINT onboarding_sample_input_chk
        CHECK (sample_input IS NULL OR (char_length(sample_input) BETWEEN 1 AND 10000 AND sample_input !~ '[\r\n]')),
    CONSTRAINT onboarding_sample_locale_chk
        CHECK (source_locale IS NULL OR source_locale IN ('vi', 'en')),
    CONSTRAINT onboarding_sample_all_or_none_chk
        CHECK ((workflow_id IS NULL) = (sample_input IS NULL) AND (workflow_id IS NULL) = (source_locale IS NULL))
);

CREATE TABLE onboarding_sample_translation (
    source_hash char(64)    NOT NULL,
    locale      varchar(2)  NOT NULL,
    input       text        NOT NULL,
    updated_by  uuid,
    updated_at  timestamptz NOT NULL,
    PRIMARY KEY (source_hash, locale),
    CONSTRAINT onboarding_sample_translation_locale_chk CHECK (locale IN ('vi', 'en')),
    CONSTRAINT onboarding_sample_translation_input_chk
        CHECK (char_length(input) BETWEEN 1 AND 10000 AND input !~ '[\r\n]')
);

INSERT INTO onboarding_sample (id, workflow_id, sample_input, source_locale, updated_by, updated_at)
VALUES (1, NULL, NULL, NULL, NULL, now());
