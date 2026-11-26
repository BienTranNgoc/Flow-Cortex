-- Additive: widens the support_ai_message.outcome CHECK with 'handoff' and 'sign_in' (every existing
-- row still satisfies it) and creates support_sla_alert, the once-per-cycle claim table of the SLA sweep.
-- The CHECK is added NOT VALID so no row is scanned while the table lock is held; V2026.11.15.231
-- validates it under a lock that does not block writers.
-- Deploy order: migration, then code.
-- Rollback: DROP TABLE support_sla_alert;
--           UPDATE support_ai_message SET outcome = 'no_answer' WHERE outcome IN ('handoff', 'sign_in');
--           then restore CHECK (outcome IN ('answered', 'no_answer', 'blocked')).

-- The inline CHECK of V2026.11.08.001 carries a generated name, so it is found by the column it covers.
DO $$
DECLARE
    outcome_check record;
BEGIN
    FOR outcome_check IN
        SELECT c.conname
          FROM pg_constraint c
          JOIN pg_attribute a ON a.attrelid = c.conrelid AND a.attnum = ANY (c.conkey)
         WHERE c.conrelid = 'support_ai_message'::regclass
           AND c.contype = 'c'
           AND a.attname = 'outcome'
    LOOP
        EXECUTE format('ALTER TABLE support_ai_message DROP CONSTRAINT %I', outcome_check.conname);
    END LOOP;
END
$$;

ALTER TABLE support_ai_message
    ADD CONSTRAINT ck_support_ai_message_outcome
    CHECK (outcome IN ('answered', 'no_answer', 'blocked', 'handoff', 'sign_in')) NOT VALID;

-- assignee_id is the planned recipient key: null means every holder of the view permission. NULLS NOT
-- DISTINCT makes two claims with a null assignee conflict, which a plain UNIQUE would let through.
CREATE TABLE support_sla_alert (
    request_id  uuid         NOT NULL REFERENCES support_request (id) ON DELETE CASCADE,
    level       text         NOT NULL CHECK (level IN ('due_soon', 'overdue')),
    cycle_at    timestamptz  NOT NULL,
    assignee_id uuid,
    created_at  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT uq_support_sla_alert UNIQUE NULLS NOT DISTINCT (request_id, level, cycle_at, assignee_id)
);

CREATE INDEX idx_support_sla_alert_created_at ON support_sla_alert (created_at);
