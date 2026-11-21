-- Workflow bookmarks and reviews: one saved row per member and workflow, one review per member and
-- workflow, its images, helpful marks and reports.
--
-- Additive: new tables only, no existing row changes. user_id and reporter_id carry no foreign
-- key: the user table belongs to another module.

CREATE TABLE workflow_bookmark (
    user_id     uuid        NOT NULL,
    workflow_id uuid        NOT NULL REFERENCES workflow (id),
    created_at  timestamptz NOT NULL,
    PRIMARY KEY (user_id, workflow_id)
);
CREATE INDEX idx_workflow_bookmark_page ON workflow_bookmark (user_id, created_at DESC, workflow_id DESC);

CREATE TABLE workflow_review (
    id            uuid          PRIMARY KEY,
    workflow_id   uuid          NOT NULL REFERENCES workflow (id),
    user_id       uuid          NOT NULL,
    rating        smallint      NOT NULL CHECK (rating BETWEEN 1 AND 5),
    body          varchar(5000),
    helpful_count integer       NOT NULL DEFAULT 0 CHECK (helpful_count >= 0),
    created_at    timestamptz   NOT NULL,
    updated_at    timestamptz   NOT NULL,
    CONSTRAINT uq_workflow_review_author UNIQUE (workflow_id, user_id)
);
CREATE INDEX idx_workflow_review_newest  ON workflow_review (workflow_id, created_at DESC, id DESC);
CREATE INDEX idx_workflow_review_helpful ON workflow_review (workflow_id, helpful_count DESC, created_at DESC, id DESC);

-- The position key is deferred: an image edit rewrites the positions of kept rows in one
-- transaction, and an immediate unique would trip on the insert-before-delete flush order.
CREATE TABLE workflow_review_image (
    id           uuid         PRIMARY KEY,
    review_id    uuid         NOT NULL REFERENCES workflow_review (id) ON DELETE CASCADE,
    position     smallint     NOT NULL CHECK (position BETWEEN 1 AND 5),
    bucket       varchar(63)  NOT NULL,
    object_key   varchar(512) NOT NULL,
    content_type varchar(100) NOT NULL,
    size_bytes   bigint       NOT NULL,
    created_at   timestamptz  NOT NULL,
    CONSTRAINT uq_workflow_review_image_position UNIQUE (review_id, position) DEFERRABLE INITIALLY DEFERRED
);

CREATE TABLE workflow_review_helpful (
    review_id  uuid        NOT NULL REFERENCES workflow_review (id) ON DELETE CASCADE,
    user_id    uuid        NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (review_id, user_id)
);

CREATE TABLE workflow_review_report (
    review_id   uuid          NOT NULL REFERENCES workflow_review (id) ON DELETE CASCADE,
    reporter_id uuid          NOT NULL,
    reason      varchar(20)   NOT NULL CHECK (reason IN ('spam','scam','harmful','harassment','misinformation','copyright','nsfw','other')),
    details     varchar(1000),
    created_at  timestamptz   NOT NULL,
    PRIMARY KEY (review_id, reporter_id)
);
