-- Moves the console's assistant switch into the settings registry, only when an admin ever flipped it
-- or it is off: the seeded never-flipped row equals the key's code default, so copying it would show
-- a stored value nobody set. support_settings is kept, unread, so a rollback to the previous release
-- still serves the switch as it was before this upgrade; a cleanup change drops the table.

INSERT INTO app_setting (key, value, updated_by, updated_at)
SELECT 'support.assistant.enabled',
       CASE WHEN assistant_enabled THEN 'true' ELSE 'false' END,
       updated_by,
       updated_at
FROM support_settings
WHERE id = 1 AND (updated_by IS NOT NULL OR assistant_enabled = false)
ON CONFLICT (key) DO NOTHING;
