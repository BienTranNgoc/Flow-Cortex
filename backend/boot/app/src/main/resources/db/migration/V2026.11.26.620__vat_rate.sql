-- VAT rate timeline and the tax split of a collected payment.
--
-- vat_rate holds the rate declared per jurisdiction and category as a half-open range [valid_from, valid_to);
-- valid_to NULL is open-ended. Overlaps and gaps are not constrained here: the resolver refuses a day that no
-- row or more than one row covers, and the publisher never leaves either. The seed is a floor of 1900-01-01
-- so every collection day is covered.
--
-- payment_transaction gains the split written once when the money is collected. Any row already collected is
-- backfilled with the split at the seeded rate before the CHECKs are added plain (not NOT VALID: Flyway runs
-- this file as one transaction, so a separate VALIDATE would run under the lock the ADD CONSTRAINT already
-- holds). The backfill touches no row when none was collected, which is the expected state.
--
-- Rollback: drop trigger payment_transaction_tax_write_once on payment_transaction and function
-- payment_transaction_tax_write_once(); drop index idx_payment_transaction_tax_rate; drop the four
-- payment_transaction_tax_* constraints; drop the columns base_amount_vnd, tax_amount_vnd, tax_rate_bps,
-- tax_rate_id and tax_inclusive from payment_transaction; drop table vat_rate. A backfilled split would be lost
-- with the columns; it is derivable again from amount_vnd at the seeded rate.
CREATE TABLE vat_rate (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    jurisdiction TEXT        NOT NULL,
    category     TEXT        NOT NULL,
    rate_bps     INTEGER     NOT NULL,
    valid_from   DATE        NOT NULL,
    valid_to     DATE,
    source_note  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT vat_rate_jurisdiction_check CHECK (jurisdiction IN ('VN')),
    CONSTRAINT vat_rate_category_check CHECK (category IN ('it_services')),
    CONSTRAINT vat_rate_rate_bps_check CHECK (rate_bps BETWEEN 0 AND 10000),
    CONSTRAINT vat_rate_range_check CHECK (valid_to IS NULL OR valid_to > valid_from),
    CONSTRAINT vat_rate_source_note_check CHECK (char_length(source_note) <= 1000),
    CONSTRAINT vat_rate_valid_from_uq UNIQUE (jurisdiction, category, valid_from)
);

INSERT INTO vat_rate (jurisdiction, category, rate_bps, valid_from, valid_to, source_note)
VALUES ('VN', 'it_services', 1000, DATE '1900-01-01', NULL,
        'Initial rate carried over from the reference platform; the 1900-01-01 start is a floor so every day is '
        'covered, not a legal effective date. Set the rate confirmed by accounting before the first collection.')
ON CONFLICT DO NOTHING;

ALTER TABLE payment_transaction
    ADD COLUMN base_amount_vnd BIGINT,
    ADD COLUMN tax_amount_vnd  BIGINT,
    ADD COLUMN tax_rate_bps    INTEGER,
    ADD COLUMN tax_rate_id     UUID REFERENCES vat_rate (id) ON DELETE RESTRICT,
    ADD COLUMN tax_inclusive   BOOLEAN;

-- Same arithmetic as TaxSplit.inclusive at 1000 bps: the base is the rounded quotient, the tax the remainder.
UPDATE payment_transaction
SET base_amount_vnd = (2 * amount_vnd * 10000 + 11000) / 22000,
    tax_amount_vnd  = amount_vnd - (2 * amount_vnd * 10000 + 11000) / 22000,
    tax_rate_bps    = 1000,
    tax_rate_id     = (SELECT id FROM vat_rate
                       WHERE jurisdiction = 'VN' AND category = 'it_services' AND valid_from = DATE '1900-01-01'),
    tax_inclusive   = TRUE
WHERE succeeded_at IS NOT NULL;

ALTER TABLE payment_transaction
    ADD CONSTRAINT payment_transaction_tax_all_or_none_check CHECK (
        (base_amount_vnd IS NULL) = (tax_amount_vnd IS NULL)
        AND (base_amount_vnd IS NULL) = (tax_rate_bps IS NULL)
        AND (base_amount_vnd IS NULL) = (tax_rate_id IS NULL)
        AND (base_amount_vnd IS NULL) = (tax_inclusive IS NULL)),
    ADD CONSTRAINT payment_transaction_tax_sum_check CHECK (
        base_amount_vnd IS NULL
        OR (base_amount_vnd >= 0 AND tax_amount_vnd >= 0 AND base_amount_vnd + tax_amount_vnd = amount_vnd)),
    ADD CONSTRAINT payment_transaction_tax_rate_check CHECK (tax_rate_bps IS NULL OR tax_rate_bps BETWEEN 0 AND 10000),
    ADD CONSTRAINT payment_transaction_tax_paid_check CHECK (succeeded_at IS NULL OR base_amount_vnd IS NOT NULL);

CREATE INDEX idx_payment_transaction_tax_rate ON payment_transaction (tax_rate_id) WHERE tax_rate_id IS NOT NULL;

CREATE FUNCTION payment_transaction_tax_write_once() RETURNS trigger AS $$
BEGIN
    IF (OLD.base_amount_vnd IS NOT NULL AND NEW.base_amount_vnd IS DISTINCT FROM OLD.base_amount_vnd)
        OR (OLD.tax_amount_vnd IS NOT NULL AND NEW.tax_amount_vnd IS DISTINCT FROM OLD.tax_amount_vnd)
        OR (OLD.tax_rate_bps IS NOT NULL AND NEW.tax_rate_bps IS DISTINCT FROM OLD.tax_rate_bps)
        OR (OLD.tax_rate_id IS NOT NULL AND NEW.tax_rate_id IS DISTINCT FROM OLD.tax_rate_id)
        OR (OLD.tax_inclusive IS NOT NULL AND NEW.tax_inclusive IS DISTINCT FROM OLD.tax_inclusive) THEN
        RAISE EXCEPTION 'the tax split of a payment is written once' USING ERRCODE = 'integrity_constraint_violation';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER payment_transaction_tax_write_once
    BEFORE UPDATE ON payment_transaction
    FOR EACH ROW EXECUTE FUNCTION payment_transaction_tax_write_once();
