-- Publish the seeded MC Product Video workflow so members can resolve it via findVisibleTo.

UPDATE workflow
SET status = 'published',
    visibility = 'public',
    updated_at = clock_timestamp()
WHERE id = '9aa35334-b672-4b6c-93e8-1a4c2cc14302';
