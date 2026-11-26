-- Fills workflow_rating_total from the existing reviews. It runs after the indexes are built so the totals
-- are read as late as the migrations allow, and in its own transaction so it holds no table-level lock on
-- workflow; the reviews are only read, and the only table written is the one the previous migration created.

INSERT INTO workflow_rating_total (workflow_id, rating_count, rating_sum)
SELECT workflow_id, count(*), sum(rating) FROM workflow_review GROUP BY workflow_id;
