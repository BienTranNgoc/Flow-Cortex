-- Plan catalogue: the tiers Nodi sells, the priced version of each tier per billing cycle, and the top-up
-- packages, seeded with Xood's visible catalogue (monthly prices, no yearly version).
--
-- A tier has no price of its own: the unretired pricing_tier_version row is the price, and
-- uq_pricing_tier_version_active refuses a second unretired version for one tier and cycle.
-- To change a price: UPDATE the unretired version SET retired_at = T; INSERT the new version with
-- effective_from = T, in one transaction. A bare INSERT of a second unretired version is refused.
--
-- Rollback (revert the code too). Run in one transaction, and include the history delete: without it
-- Flyway still counts this version as applied and a later roll-forward skips the migration, leaving the
-- tables missing.
--   BEGIN;
--   DROP TABLE pricing_tier_version, pricing_tier, topup_package;
--   DELETE FROM flyway_schema_history WHERE version = '2026.11.26.610';
--   COMMIT;
-- This holds only until a later migration references these tables; after that, roll back with a forward
-- migration.

CREATE TABLE pricing_tier (
    id                 TEXT        PRIMARY KEY,
    name               TEXT        NOT NULL,
    name_en            TEXT,
    tagline_vi         TEXT,
    tagline_en         TEXT,
    marketing_features JSONB       NOT NULL DEFAULT '{"vi":[],"en":[]}',
    popular            BOOLEAN     NOT NULL DEFAULT false,
    is_active          BOOLEAN     NOT NULL DEFAULT true,
    sort_order         INTEGER     NOT NULL DEFAULT 0,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pricing_tier_id_check CHECK (id ~ '^[a-z][a-z0-9_]{0,19}$'),
    CONSTRAINT pricing_tier_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 40),
    CONSTRAINT pricing_tier_name_en_check CHECK (name_en IS NULL OR char_length(name_en) <= 40),
    CONSTRAINT pricing_tier_tagline_check CHECK (
        char_length(tagline_vi) <= 160 AND char_length(tagline_en) <= 160),
    CONSTRAINT pricing_tier_marketing_features_check CHECK (jsonb_typeof(marketing_features) = 'object')
);

-- At most one tier carries the popular mark.
CREATE UNIQUE INDEX uq_pricing_tier_popular ON pricing_tier ((true)) WHERE popular;

CREATE TABLE pricing_tier_version (
    id                         UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    tier_id                    TEXT        NOT NULL REFERENCES pricing_tier (id),
    billing_cycle              TEXT        NOT NULL,
    price_vnd                  BIGINT      NOT NULL,
    included_credits_per_month BIGINT      NOT NULL,
    effective_from             TIMESTAMPTZ NOT NULL,
    retired_at                 TIMESTAMPTZ,
    created_at                 TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT pricing_tier_version_cycle_check CHECK (billing_cycle IN ('monthly', 'yearly')),
    CONSTRAINT pricing_tier_version_price_check CHECK (price_vnd >= 0),
    CONSTRAINT pricing_tier_version_credits_check CHECK (included_credits_per_month >= 0),
    CONSTRAINT pricing_tier_version_retired_check CHECK (retired_at IS NULL OR retired_at >= effective_from)
);

CREATE UNIQUE INDEX uq_pricing_tier_version_active
    ON pricing_tier_version (tier_id, billing_cycle) WHERE retired_at IS NULL;

CREATE TABLE topup_package (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT        NOT NULL,
    name_en        TEXT,
    description    TEXT,
    description_en TEXT,
    eyebrow        TEXT,
    eyebrow_en     TEXT,
    price_vnd      BIGINT      NOT NULL,
    credits        BIGINT      NOT NULL,
    group_key      TEXT,
    active         BOOLEAN     NOT NULL DEFAULT true,
    sort_order     INTEGER     NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT topup_package_name_check CHECK (char_length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT topup_package_name_en_check CHECK (name_en IS NULL OR char_length(name_en) <= 80),
    CONSTRAINT topup_package_copy_check CHECK (
        char_length(description) <= 160 AND char_length(description_en) <= 160
        AND char_length(eyebrow) <= 40 AND char_length(eyebrow_en) <= 40),
    CONSTRAINT topup_package_price_check CHECK (price_vnd > 0),
    CONSTRAINT topup_package_credits_check CHECK (credits > 0),
    CONSTRAINT topup_package_group_key_check CHECK (group_key IS NULL OR char_length(group_key) BETWEEN 1 AND 40)
);

CREATE INDEX idx_topup_package_offered ON topup_package (sort_order) WHERE active;

-- Seed: Xood's visible catalogue. Positions 0-3; no tier is marked popular; the free tier has no marketing bullets.
INSERT INTO pricing_tier (id, name, name_en, tagline_vi, tagline_en, marketing_features, sort_order) VALUES
    ('free', 'Free', 'Free',
        'Trải nghiệm chạy thử workflow', 'Try a workflow end to end',
        '{"vi":[],"en":[]}', 0),
    ('basic', 'Cơ bản', 'Starter',
        'Đủ cho việc lặp lại hằng ngày của một người', 'Enough for one person''s daily routine',
        '{"vi":["45.000 Credit/tháng","Toàn bộ workflow","Ưu tiên chạy tiêu chuẩn"],"en":["45,000 credits per month","Every workflow","Standard run priority"]}',
        1),
    ('advanced', 'Nâng cao', 'Advanced',
        'Chạy hàng loạt theo danh sách khách mỗi ngày', 'Run batches from your client list every day',
        '{"vi":["120.000 Credit/tháng","Builder Studio đầy đủ","Ưu tiên chạy cao hơn"],"en":["120,000 credits per month","The full Builder Studio","Higher run priority"]}',
        2),
    ('professional', 'Chuyên nghiệp', 'Professional',
        'Khối lượng lớn, chạy ổn định cho nhiều khách', 'High volume, steady runs for many clients',
        '{"vi":["300.000 Credit/tháng","Hạn mức cao nhất","Hỗ trợ ưu tiên"],"en":["300,000 credits per month","The highest limits","Priority support"]}',
        3);

INSERT INTO pricing_tier_version (tier_id, billing_cycle, price_vnd, included_credits_per_month, effective_from) VALUES
    ('free', 'monthly', 0, 0, now()),
    ('basic', 'monthly', 849000, 45000, now()),
    ('advanced', 'monthly', 2499000, 120000, now()),
    ('professional', 'monthly', 5799000, 300000, now());

-- Fixed ids so every environment holds the same package ids.
INSERT INTO topup_package (id, name, description, price_vnd, credits, group_key, sort_order) VALUES
    ('a1000000-0000-4000-8000-000000000001', 'Trải Nghiệm',
        'Dành cho tác vụ nhỏ hoặc dùng thử thêm', 50000, 10000, NULL, 1),
    ('a1000000-0000-4000-8000-000000000002', 'Tiết kiệm',
        'Cho các dự án vừa và ngắn hạn', 150000, 35000, NULL, 2),
    ('a1000000-0000-4000-8000-000000000003', 'Linh hoạt 50.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 300000, 50000, 'flexible', 3),
    ('a1000000-0000-4000-8000-000000000004', 'Linh hoạt 60.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 350000, 60000, 'flexible', 4),
    ('a1000000-0000-4000-8000-000000000005', 'Linh hoạt 70.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 400000, 70000, 'flexible', 5),
    ('a1000000-0000-4000-8000-000000000006', 'Linh hoạt 80.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 450000, 80000, 'flexible', 6),
    ('a1000000-0000-4000-8000-000000000007', 'Linh hoạt 90.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 500000, 90000, 'flexible', 7),
    ('a1000000-0000-4000-8000-000000000008', 'Linh hoạt 100.000',
        'Tự chọn mức credit — trả đúng nhu cầu', 550000, 100000, 'flexible', 8);
