-- Token-bucket state for every rate limit, shared by all backend instances (Bucket4j JDBC proxy
-- manager). expires_at is epoch milliseconds: the moment the bucket has refilled to capacity.
CREATE TABLE rate_limit_bucket (
    id         VARCHAR(128) PRIMARY KEY,
    state      BYTEA,
    expires_at BIGINT
);

CREATE INDEX rate_limit_bucket_expires_at_idx ON rate_limit_bucket (expires_at);
