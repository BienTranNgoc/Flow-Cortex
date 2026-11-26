package com.flowcortex.asset.domain;

import com.flowcortex.asset.domain.AssetQuery.Filters;
import com.flowcortex.asset.domain.AssetQuery.ListQuery;
import com.flowcortex.asset.domain.AssetQuery.Sort;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The keyset position after the last asset of a page: the sort tuple of that asset plus the walk's
 * mark. Encoded as base64url JSON, it is opaque to the client and carries no space or user; it is
 * bound to the sort and the filters it was issued for, so a cursor replayed against another query is
 * refused instead of silently paging the wrong set.
 */
public record AssetCursor(Sort sort, String fingerprint, Instant mark, Instant createdAt, UUID id, Long bytes) {

    static final int VERSION = 1;
    static final int MAX_TOKEN_LENGTH = 1024;
    private static final String PARAMETER = "cursor";
    private static final int FINGERPRINT_BYTES = 8;
    private static final JsonMapper JSON = JsonMapper.builder().build();

    /**
     * The cursor after the given asset. {@code bytes} is kept only when the sort starts with the byte
     * size.
     */
    public static AssetCursor after(ListQuery query, Instant mark, Instant createdAt, UUID id, long bytes) {
        return new AssetCursor(query.sort(), fingerprint(query.filters()), mark, createdAt, id,
                query.sort().bySize() ? bytes : null);
    }

    /** First 16 hex characters of the SHA-256 of the canonical filter. */
    public static String fingerprint(Filters filters) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(filters.canonical().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest, 0, FINGERPRINT_BYTES);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is part of every Java runtime", e);
        }
    }

    public String encode() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("v", VERSION);
        fields.put("sort", sort.value());
        fields.put("fp", fingerprint);
        fields.put("mark", mark.toString());
        fields.put("createdAt", createdAt.toString());
        fields.put("id", id.toString());
        if (bytes != null) {
            fields.put("bytes", bytes);
        }
        return Base64.getUrlEncoder().withoutPadding().encodeToString(JSON.writeValueAsBytes(fields));
    }

    /**
     * Reads a token issued for {@code query}'s sort and filters. A {@code mark} sent with it must be
     * the cursor's own; without one the cursor's mark is the walk's.
     *
     * @throws AssetRequestRejected naming {@code cursor} for anything else
     */
    public static AssetCursor decode(String token, ListQuery query) {
        if (token == null || token.isEmpty() || token.length() > MAX_TOKEN_LENGTH) {
            throw rejected();
        }
        AssetCursor cursor = read(token);
        if (cursor.sort != query.sort()
                || !cursor.fingerprint.equals(fingerprint(query.filters()))
                || (query.mark() != null && !query.mark().equals(cursor.mark))) {
            throw rejected();
        }
        return cursor;
    }

    private static AssetCursor read(String token) {
        JsonNode node;
        try {
            node = JSON.readTree(Base64.getUrlDecoder().decode(token));
        } catch (IllegalArgumentException | JacksonException e) {
            throw rejected();
        }
        if (!node.isObject() || !node.has("v") || !node.get("v").isInt() || node.get("v").intValue() != VERSION) {
            throw rejected();
        }
        Sort sort = sortOf(text(node, "sort"));
        String fp = text(node, "fp");
        Instant mark = instant(text(node, "mark"));
        Instant createdAt = instant(text(node, "createdAt"));
        UUID id = uuid(text(node, "id"));
        Long bytes = null;
        if (sort.bySize()) {
            JsonNode value = node.get("bytes");
            if (value == null || !value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 0) {
                throw rejected();
            }
            bytes = value.longValue();
        }
        return new AssetCursor(sort, fp, mark, createdAt, id, bytes);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isString()) {
            throw rejected();
        }
        return value.stringValue();
    }

    private static Sort sortOf(String value) {
        for (Sort sort : Sort.values()) {
            if (sort.value().equals(value)) {
                return sort;
            }
        }
        throw rejected();
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw rejected();
        }
    }

    private static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException e) {
            throw rejected();
        }
    }

    private static AssetRequestRejected rejected() {
        return new AssetRequestRejected(PARAMETER);
    }
}
