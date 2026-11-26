package com.flowcortex.app.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.AbstractPostgresBootTest;
import com.flowcortex.asset.domain.NewSpaceAsset;
import com.flowcortex.asset.infrastructure.SpaceAssetStore;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The columns and the tag table the library reads, against a real Postgres: the folding function
 * the generated search columns share, and the constraints the tag writer relies on.
 */
@Testcontainers
@SpringBootTest
class AssetLibrarySchemaIT extends AbstractPostgresBootTest {

    private static final Instant T0 = Instant.parse("2026-11-15T10:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private NamedParameterJdbcTemplate named;

    private SpaceAssetStore assets;

    @BeforeEach
    void setUp() {
        clean();
        assets = new SpaceAssetStore(named, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @AfterEach
    void clean() {
        jdbc.update("DELETE FROM space_asset_tag");
        jdbc.update("DELETE FROM space_asset");
    }

    @Test
    void titleSearchFoldsVietnameseAccentsCaseAndDStroke() {
        UUID id = insertAsset("Đồng HỒ Ếch");

        assertThat(titleSearch(id)).isEqualTo("dong ho ech");
        assertThat(jdbc.queryForObject("SELECT asset_search_fold(?)", String.class, "ĐƯỜNG Đi")).isEqualTo("duong di");
    }

    @Test
    void aRowInsertedBeforeTheColumnExistsIsFolded() {
        UUID id = insertAsset("Áo thun mùa hè");

        assertThat(titleSearch(id)).isEqualTo("ao thun mua he");
    }

    @Test
    void tagKeyKeepsOneTagPerFoldedKey() {
        UUID id = insertAsset("Clip");

        int first = insertTag(id, "Mùa hè");
        int second = insertTag(id, "mua he");

        assertThat(first).isEqualTo(1);
        assertThat(second).isZero();
        assertThat(jdbc.queryForList("SELECT label FROM space_asset_tag WHERE asset_id = ?", String.class, id))
                .containsExactly("Mùa hè");
        assertThat(jdbc.queryForObject("SELECT tag_key FROM space_asset_tag WHERE asset_id = ?", String.class, id))
                .isEqualTo("mua he");
    }

    @Test
    void tagLabelOutside1To40IsRefused() {
        UUID id = insertAsset("Clip");

        assertThatThrownBy(() -> insertTag(id, "")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertTag(id, "x".repeat(41))).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(insertTag(id, "x".repeat(40))).isEqualTo(1);
    }

    @Test
    void deletingAnAssetDeletesItsTags() {
        UUID id = insertAsset("Clip");
        UUID other = insertAsset("Other");
        insertTag(id, "one");
        insertTag(id, "two");
        insertTag(other, "one");

        jdbc.update("DELETE FROM space_asset WHERE id = ?", id);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_asset_tag WHERE asset_id = ?", Integer.class, id))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM space_asset_tag WHERE asset_id = ?", Integer.class,
                other)).isEqualTo(1);
    }

    @Test
    void favoriteDefaultsToFalseAndMissingAtToNull() {
        UUID id = insertAsset("Clip");

        assertThat(jdbc.queryForObject("SELECT favorite FROM space_asset WHERE id = ?", Boolean.class, id)).isFalse();
        assertThat(jdbc.queryForObject("SELECT missing_at FROM space_asset WHERE id = ?", Timestamp.class, id))
                .isNull();
    }

    @Test
    void theSpaceCreatedIndexServesTheNewestFirstWalk() {
        assertThat(jdbc.queryForObject("SELECT pg_get_indexdef(indexrelid) FROM pg_index "
                + "WHERE indexrelid = 'idx_space_asset_space_created'::regclass", String.class))
                .contains("(space_id, created_at DESC, id DESC)");
    }

    private UUID insertAsset(String title) {
        UUID id = UUID.randomUUID();
        UUID media = UUID.randomUUID();
        boolean inserted = assets.insertIfAbsent(new NewSpaceAsset(id, UUID.randomUUID(), UUID.randomUUID(), media,
                "upload", "video", title, title, 1, "media", "k/" + media, "video/mp4", 10L, UUID.randomUUID(), null,
                null, null));
        assertThat(inserted).isTrue();
        return id;
    }

    private String titleSearch(UUID id) {
        return jdbc.queryForObject("SELECT title_search FROM space_asset WHERE id = ?", String.class, id);
    }

    private int insertTag(UUID assetId, String label) {
        return jdbc.update("INSERT INTO space_asset_tag (asset_id, space_id, label, created_at) "
                + "VALUES (?, ?, ?, ?) ON CONFLICT (asset_id, tag_key) DO NOTHING",
                assetId, UUID.randomUUID(), label, Timestamp.from(T0));
    }
}
