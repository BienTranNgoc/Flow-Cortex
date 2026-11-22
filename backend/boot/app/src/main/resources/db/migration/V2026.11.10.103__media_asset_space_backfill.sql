-- Gives every asset owner a personal space and assigns every asset that has none; every statement
-- is idempotent so the file can be re-run safely.
-- Owners are taken from media_asset itself, confirmed or not and whether or not the account still
-- exists: media_asset_binding references these rows, so they can be neither deleted nor left
-- without a space once the column is NOT NULL.

INSERT INTO space (id, owner_id, is_default, created_at)
SELECT gen_random_uuid(), o.owner_id, true, now()
  FROM (SELECT DISTINCT owner_id FROM media_asset) o
ON CONFLICT (owner_id) WHERE is_default DO NOTHING;

INSERT INTO space_member (space_id, user_id, role, created_at)
SELECT s.id, s.owner_id, 'manager', now() FROM space s WHERE s.is_default
ON CONFLICT (space_id, user_id) DO NOTHING;

UPDATE media_asset a SET space_id = s.id
  FROM space s
 WHERE s.owner_id = a.owner_id AND s.is_default AND a.space_id IS NULL;
