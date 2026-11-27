-- A member's invoice details: who an e-invoice is issued to.
--
-- The CHECKs hold the length bounds, the personal ID shape and the forced nulls. The tax-code shape and the
-- "required" rules stay in the application rule, so a change of the tax-code format or of a required field
-- needs no CHECK rewrite over rows of personal data. One default per member is the partial unique index;
-- writers take a per-member advisory lock first, so the index is the last line of defence, not the arbiter.
-- Profiles go with their owner (ON DELETE CASCADE).
--
-- Rollback: drop table billing_profile. Nothing else references it.
CREATE TABLE billing_profile (
    id                 UUID        PRIMARY KEY,
    user_id            UUID        NOT NULL REFERENCES app_user (id) ON DELETE CASCADE,
    type               TEXT        NOT NULL,
    legal_name         TEXT        NOT NULL,
    tax_code           TEXT,
    personal_id_number TEXT,
    address            TEXT        NOT NULL,
    email              TEXT,
    is_default         BOOLEAN     NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ NOT NULL,
    updated_at         TIMESTAMPTZ NOT NULL,
    CONSTRAINT billing_profile_type_check CHECK (type IN ('personal', 'business', 'household')),
    CONSTRAINT billing_profile_legal_name_check CHECK (char_length(legal_name) BETWEEN 1 AND 400),
    CONSTRAINT billing_profile_address_check CHECK (char_length(address) BETWEEN 1 AND 500),
    CONSTRAINT billing_profile_email_check CHECK (char_length(email) <= 255),
    CONSTRAINT billing_profile_personal_id_check CHECK (personal_id_number ~ '^\d{12}$'),
    CONSTRAINT billing_profile_personal_id_type_check CHECK (type = 'personal' OR personal_id_number IS NULL),
    CONSTRAINT billing_profile_tax_code_type_check CHECK (type <> 'personal' OR tax_code IS NULL)
);

CREATE INDEX idx_billing_profile_user ON billing_profile (user_id, created_at, id);

CREATE UNIQUE INDEX uq_billing_profile_default ON billing_profile (user_id) WHERE is_default;
