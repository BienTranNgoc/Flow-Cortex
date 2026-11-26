-- The publication of the payment terms in force when a payment was opened. No foreign key: the publication
-- lives in the content module's table and publications are never deleted; null means none was published.
--
-- Rollback: drop column payment_transaction.terms_revision_id.
ALTER TABLE payment_transaction ADD COLUMN terms_revision_id UUID;
