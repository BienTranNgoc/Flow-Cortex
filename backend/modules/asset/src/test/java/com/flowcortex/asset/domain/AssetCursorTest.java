package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.asset.domain.AssetQuery.ListQuery;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AssetCursorTest {

    private static final Instant NOW = Instant.parse("2026-11-15T03:00:00Z");
    private static final Instant MARK = Instant.parse("2026-11-15T02:00:00.123456Z");
    private static final Instant CREATED = Instant.parse("2026-11-14T10:15:30.654321Z");
    private static final UUID ID = UUID.fromString("0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70");

    private static ListQuery query(String... keyValues) {
        Map<String, List<String>> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.computeIfAbsent(keyValues[i], key -> new ArrayList<>()).add(keyValues[i + 1]);
        }
        return ListQuery.parse(params, NOW);
    }

    private static String token(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertRejectsCursor(String token, ListQuery query) {
        assertThatThrownBy(() -> AssetCursor.decode(token, query))
                .isInstanceOfSatisfying(AssetRequestRejected.class,
                        rejected -> assertThat(rejected.parameter()).isEqualTo("cursor"));
    }

    @Test
    void roundTripKeepsMicrosecondsAndBytes() {
        ListQuery bySize = query("sort", "size_desc", "kind", "image");
        String token = AssetCursor.after(bySize, MARK, CREATED, ID, 5_000_000_000L).encode();

        AssetCursor decoded = AssetCursor.decode(token, bySize);

        assertThat(decoded.sort()).isEqualTo(AssetQuery.Sort.SIZE_DESC);
        assertThat(decoded.mark()).isEqualTo(MARK);
        assertThat(decoded.createdAt()).isEqualTo(CREATED);
        assertThat(decoded.id()).isEqualTo(ID);
        assertThat(decoded.bytes()).isEqualTo(5_000_000_000L);
        assertThat(token).matches("[A-Za-z0-9_-]+");
    }

    @Test
    void aTimeSortedCursorHasNoBytes() {
        ListQuery newest = query();
        AssetCursor decoded = AssetCursor.decode(AssetCursor.after(newest, MARK, CREATED, ID, 42L).encode(), newest);

        assertThat(decoded.bytes()).isNull();
        assertThat(decoded.createdAt()).isEqualTo(CREATED);
    }

    @Test
    void cursorCarriesNoSpaceOrUser() {
        String token = AssetCursor.after(query(), MARK, CREATED, ID, 1L).encode();
        String json = new String(Base64.getUrlDecoder().decode(token), StandardCharsets.UTF_8);

        Map<?, ?> fields = JsonMapper.builder().build().readValue(json, Map.class);

        assertThat(new TreeSet<Object>(fields.keySet())).containsExactly("createdAt", "fp", "id", "mark", "sort", "v");
        assertThat(fields.get("v")).isEqualTo(1);
    }

    @Test
    void malformedBase64OrJsonOrVersionIsRejected() {
        ListQuery query = query();
        String fp = AssetCursor.fingerprint(query.filters());
        String good = "\"sort\":\"newest\",\"fp\":\"" + fp + "\",\"mark\":\"" + MARK + "\",\"createdAt\":\"" + CREATED
                + "\",\"id\":\"" + ID + "\"";

        assertThat(AssetCursor.decode(token("{\"v\":1," + good + "}"), query).id()).isEqualTo(ID);

        assertRejectsCursor("abc", query);
        assertRejectsCursor("!!not base64!!", query);
        assertRejectsCursor("", query);
        assertRejectsCursor(token("not json"), query);
        assertRejectsCursor(token("[]"), query);
        assertRejectsCursor(token("{\"v\":2," + good + "}"), query);
        assertRejectsCursor(token("{\"v\":\"1\"," + good + "}"), query);
        assertRejectsCursor(token("{" + good + "}"), query);
        assertRejectsCursor(token("{\"v\":1,\"sort\":\"newest\"}"), query);
        assertRejectsCursor(token("{\"v\":1," + good.replace(ID.toString(), "not-a-uuid") + "}"), query);
        assertRejectsCursor(token("{\"v\":1," + good.replace(CREATED.toString(), "yesterday") + "}"), query);
        assertRejectsCursor(token("{\"v\":1," + good.replace(MARK.toString(), "") + "}"), query);
        assertRejectsCursor("A".repeat(2000), query);
    }

    @Test
    void aSizeCursorWithoutBytesIsRejected() {
        ListQuery bySize = query("sort", "size_asc");
        String fp = AssetCursor.fingerprint(bySize.filters());
        String json = "{\"v\":1,\"sort\":\"size_asc\",\"fp\":\"" + fp + "\",\"mark\":\"" + MARK + "\",\"createdAt\":\"" + CREATED
                + "\",\"id\":\"" + ID + "\"}";

        assertRejectsCursor(token(json), bySize);
        assertRejectsCursor(token(json.replace("\"id\"", "\"bytes\":-1,\"id\"")), bySize);
    }

    @Test
    void cursorFromAnotherSortIsRejected() {
        String token = AssetCursor.after(query(), MARK, CREATED, ID, 1L).encode();

        assertRejectsCursor(token, query("sort", "size_desc"));
        assertRejectsCursor(token, query("sort", "oldest"));
    }

    @Test
    void cursorFromAnotherFilterSetIsRejected() {
        String token = AssetCursor.after(query("kind", "image"), MARK, CREATED, ID, 1L).encode();

        assertRejectsCursor(token, query("kind", "video"));
        assertRejectsCursor(token, query());
        assertRejectsCursor(token, query("kind", "image", "q", "ao"));
        assertRejectsCursor(token, query("kind", "image", "favorite", "true"));
        assertThat(AssetCursor.decode(token, query("kind", "image", "pageSize", "10")).id()).isEqualTo(ID);
    }

    @Test
    void fingerprintIgnoresCsvOrderAndDuplicates() {
        String first = AssetCursor.fingerprint(query("kind", "video,image,video", "source", "upload,run").filters());
        String second = AssetCursor.fingerprint(query("kind", "image,,video", "source", "run,upload").filters());

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{16}");
    }

    @Test
    void fingerprintDiffersForAnotherCustomRange() {
        String november = AssetCursor.fingerprint(
                query("time", "custom", "from", "2026-11-01", "to", "2026-11-02").filters());
        String laterEnd = AssetCursor.fingerprint(
                query("time", "custom", "from", "2026-11-01", "to", "2026-11-03").filters());

        assertThat(november).isNotEqualTo(laterEnd);
        assertThat(AssetCursor.fingerprint(query("time", "7d").filters()))
                .isNotEqualTo(AssetCursor.fingerprint(query("time", "30d").filters()));
    }

    @Test
    void fingerprintSeparatesFieldsThatShareCharacters() {
        assertThat(AssetCursor.fingerprint(query("q", "a", "tag", "b").filters()))
                .isNotEqualTo(AssetCursor.fingerprint(query("q", "a\ntag=1:b").filters()));
        assertThat(AssetCursor.fingerprint(query("q", "Áo").filters()))
                .isNotEqualTo(AssetCursor.fingerprint(query("q", "ao").filters()));
    }

    @Test
    void cursorWithAnotherMarkIsRejectedNamingCursor() {
        String token = AssetCursor.after(query(), MARK, CREATED, ID, 1L).encode();

        assertRejectsCursor(token, query("mark", MARK.minusSeconds(1).toString()));
        assertThat(AssetCursor.decode(token, query("mark", MARK.toString())).mark()).isEqualTo(MARK);
        assertThat(AssetCursor.decode(token, query()).mark()).isEqualTo(MARK);
    }
}
