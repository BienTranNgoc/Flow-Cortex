-- Every media asset belongs to its owner's personal space. No foreign key, like the other
-- cross-module tables, so the space rows of a deleted account stay without blocking anything.

ALTER TABLE media_asset ALTER COLUMN space_id SET NOT NULL;

CREATE INDEX idx_media_asset_space_id ON media_asset (space_id);
