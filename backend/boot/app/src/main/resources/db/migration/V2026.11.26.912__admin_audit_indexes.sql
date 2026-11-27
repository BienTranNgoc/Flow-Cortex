-- Indexes for every audit viewer query shape, built CONCURRENTLY so admin_audit_event writes are not blocked while
-- a table that only grows is scanned. Flyway runs a script made only of CONCURRENTLY statements outside a
-- transaction, so keep this file to those statements. A build that fails leaves an INVALID index; the DROP ... IF
-- EXISTS lines clear it so the migration can simply be retried. The search index needs pg_trgm from
-- V2026.11.26.910.

-- Keyset paging: every filter shape leads with its equality columns and ends in the (occurred_at, id) order.
DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_page ON admin_audit_event (occurred_at DESC, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_actor_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_actor_page
    ON admin_audit_event (actor_user_id, occurred_at DESC, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_action_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_action_page ON admin_audit_event (action, occurred_at DESC, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_type_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_type_page
    ON admin_audit_event (resource_type, occurred_at DESC, id DESC);

DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_resource_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_resource_page
    ON admin_audit_event (resource_type, resource_id, occurred_at DESC, id DESC);

-- A member's timeline reads the 'user' and 'admin_account' rows about one id; an IN list on the leading column of
-- the index above would return them grouped by type and force a sort, so this partial index orders them directly.
DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_member_page;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_member_page ON admin_audit_event (resource_id, occurred_at DESC, id DESC)
    WHERE resource_type IN ('user', 'admin_account');

-- The search predicate must stay byte-identical to this expression for the planner to use the index.
DROP INDEX CONCURRENTLY IF EXISTS idx_admin_audit_event_search_trgm;
CREATE INDEX CONCURRENTLY idx_admin_audit_event_search_trgm ON admin_audit_event USING gin (
    lower(coalesce(actor_email, '') || ' ' || coalesce(actor_name, '') || ' '
        || coalesce(resource_id, '') || ' ' || coalesce(resource_label, '')) gin_trgm_ops);
