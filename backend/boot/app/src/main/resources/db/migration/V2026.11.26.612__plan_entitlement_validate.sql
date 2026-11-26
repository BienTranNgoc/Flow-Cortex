-- Validates the constraints V2026.11.24.201 added NOT VALID. VALIDATE CONSTRAINT scans the table under
-- SHARE UPDATE EXCLUSIVE, which does not block the ledger's reads or writes of payment_transaction; the two
-- period columns are new and every existing row holds NULL in both, so it cannot fail on existing data.

ALTER TABLE payment_transaction VALIDATE CONSTRAINT payment_transaction_period_pair_check;
ALTER TABLE payment_transaction VALIDATE CONSTRAINT payment_transaction_period_order_check;
ALTER TABLE payment_transaction VALIDATE CONSTRAINT payment_transaction_period_kind_check;
