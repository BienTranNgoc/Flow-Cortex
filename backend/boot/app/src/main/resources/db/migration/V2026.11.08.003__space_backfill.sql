-- Personal-space backfill; every statement is idempotent so the file can be re-run safely.
-- Only confirmed accounts get a space here: an unconfirmed one may still be purged, and receives
-- its space when it is activated.

INSERT INTO space (id, owner_id, is_default, created_at)
SELECT gen_random_uuid(), u.id, true, now()
  FROM app_user u
 WHERE u.email_confirmed_at IS NOT NULL
ON CONFLICT (owner_id) WHERE is_default DO NOTHING;

INSERT INTO space_member (space_id, user_id, role, created_at)
SELECT s.id, s.owner_id, 'manager', now() FROM space s WHERE s.is_default
ON CONFLICT (space_id, user_id) DO NOTHING;

UPDATE media_asset a SET space_id = s.id
  FROM space s
 WHERE s.owner_id = a.owner_id AND s.is_default AND a.space_id IS NULL;
