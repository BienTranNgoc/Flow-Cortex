package com.flowcortex.drive.domain;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The Memory list query, parsed and validated: values only reach SQL as named parameters, and the
 * fragments it emits name the {@code memory_result} columns directly.
 */
public record MemoryFilter(String q, Set<ContentType> types, Instant from, Instant to, Set<SizeBucket> sizes, Sort sort,
        int page, int pageSize) {

    public static final int Q_MAX = 200;
    public static final int PAGE_SIZE_MAX = 100;
    private static final long ONE_MIB = 1_048_576L;
    private static final long FIFTY_MIB = 52_428_800L;
    private static final String VALUE_INVALID = "drive.value.invalid";

    /** Size facets of an entry; the bounds follow the sizes members read in the app. */
    public enum SizeBucket {
        LT_1MB("lt1mb", "size_bytes < " + ONE_MIB),
        BETWEEN_1_AND_50MB("1to50mb", "size_bytes >= " + ONE_MIB + " AND size_bytes <= " + FIFTY_MIB),
        GT_50MB("gt50mb", "size_bytes > " + FIFTY_MIB);

        private final String wire;
        private final String predicate;

        SizeBucket(String wire, String predicate) {
            this.wire = wire;
            this.predicate = predicate;
        }

        public String wire() {
            return wire;
        }

        /** SQL over {@code size_bytes} that holds exactly for the entries of this bucket. */
        public String predicate() {
            return predicate;
        }

        public static SizeBucket of(long bytes) {
            if (bytes < ONE_MIB) {
                return LT_1MB;
            }
            return bytes <= FIFTY_MIB ? BETWEEN_1_AND_50MB : GT_50MB;
        }

        public static Optional<SizeBucket> fromWire(String value) {
            for (SizeBucket bucket : values()) {
                if (bucket.wire.equals(value)) {
                    return Optional.of(bucket);
                }
            }
            return Optional.empty();
        }
    }

    /** Sort orders; every one ends on {@code id} so paging never repeats or skips a row. */
    public enum Sort {
        NEWEST("newest", "result_at DESC, id DESC"),
        OLDEST("oldest", "result_at ASC, id ASC"),
        SIZE_DESC("size_desc", "size_bytes DESC, result_at DESC, id DESC"),
        SIZE_ASC("size_asc", "size_bytes ASC, result_at DESC, id DESC");

        private final String wire;
        private final String orderBy;

        Sort(String wire, String orderBy) {
            this.wire = wire;
            this.orderBy = orderBy;
        }

        static Optional<Sort> fromWire(String value) {
            for (Sort sort : values()) {
                if (sort.wire.equals(value)) {
                    return Optional.of(sort);
                }
            }
            return Optional.empty();
        }
    }

    /** A WHERE body (no keyword) with the named parameters it uses. */
    public record Sql(String where, Map<String, Object> params) {
    }

    /** The outcome of parsing: a filter, or the field to message-key errors that refused it. */
    public record Parsed(MemoryFilter filter, Map<String, String> errors) {
    }

    /**
     * {@code time} is {@code all}, {@code 7d}, {@code 30d} or {@code custom}; blank values mean no filter.
     * {@code types} and {@code sizes} may repeat: an entry matches any of the given values.
     */
    public static Parsed parse(String q, List<String> types, String time, Instant from, Instant to, List<String> sizes,
            String sort,
            int page, int pageSize, Instant now) {
        Map<String, String> errors = new LinkedHashMap<>();
        String query = q == null || q.isBlank() ? null : q.strip();
        if (query != null && query.length() > Q_MAX) {
            errors.put("q", VALUE_INVALID);
        }
        Set<ContentType> kinds = new LinkedHashSet<>();
        for (String type : nonBlank(types)) {
            Optional<ContentType> kind = ContentType.fromWire(type);
            if (kind.isEmpty()) {
                errors.put("type", VALUE_INVALID);
            } else {
                kinds.add(kind.get());
            }
        }
        Set<SizeBucket> buckets = new LinkedHashSet<>();
        for (String size : nonBlank(sizes)) {
            Optional<SizeBucket> bucket = SizeBucket.fromWire(size);
            if (bucket.isEmpty()) {
                errors.put("size", VALUE_INVALID);
            } else {
                buckets.add(bucket.get());
            }
        }
        Sort order = Sort.NEWEST;
        if (!isBlank(sort)) {
            order = Sort.fromWire(sort).orElse(null);
            if (order == null) {
                errors.put("sort", VALUE_INVALID);
            }
        }
        if (page < 0) {
            errors.put("page", VALUE_INVALID);
        }
        if (pageSize < 1 || pageSize > PAGE_SIZE_MAX) {
            errors.put("pageSize", VALUE_INVALID);
        }
        Instant lower = null;
        Instant upper = null;
        String window = isBlank(time) ? "all" : time;
        switch (window) {
            case "all" -> lower = null;
            case "7d" -> lower = now.minus(Duration.ofDays(7));
            case "30d" -> lower = now.minus(Duration.ofDays(30));
            case "custom" -> {
                lower = from;
                upper = to;
                if (from == null && to == null) {
                    errors.put("time", VALUE_INVALID);
                } else if (from != null && to != null && from.isAfter(to)) {
                    errors.put("from", "drive.time.rangeInvalid");
                }
            }
            default -> errors.put("time", VALUE_INVALID);
        }
        if (!errors.isEmpty()) {
            return new Parsed(null, errors);
        }
        return new Parsed(new MemoryFilter(query, Set.copyOf(kinds), lower, upper, Set.copyOf(buckets), order, page,
                pageSize), Map.of());
    }

    /** Escapes {@code \}, {@code %} and {@code _} so a search term matches literally under {@code ESCAPE '\'}. */
    public static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** Every filter, the size bucket included, scoped to the space's visible entries. */
    public Sql where(UUID spaceId) {
        return build(spaceId, true);
    }

    /** Every filter except the size bucket: the base of the size facet counts. */
    public Sql whereIgnoringSize(UUID spaceId) {
        return build(spaceId, false);
    }

    public String orderBy() {
        return sort.orderBy;
    }

    public int offset() {
        return page * pageSize;
    }

    private Sql build(UUID spaceId, boolean withSize) {
        Map<String, Object> params = new LinkedHashMap<>();
        List<String> clauses = new ArrayList<>(List.of("space_id = :spaceId", "NOT hidden"));
        params.put("spaceId", spaceId);
        if (q != null) {
            clauses.add("title ILIKE :q ESCAPE '\\'");
            params.put("q", "%" + escapeLike(q) + "%");
        }
        if (!types.isEmpty()) {
            clauses.add("kind IN (:types)");
            params.put("types", types.stream().map(ContentType::wire).sorted().toList());
        }
        if (from != null) {
            clauses.add("result_at >= :from");
            params.put("from", from.atOffset(ZoneOffset.UTC));
        }
        if (to != null) {
            clauses.add("result_at <= :to");
            params.put("to", to.atOffset(ZoneOffset.UTC));
        }
        if (withSize && !sizes.isEmpty()) {
            clauses.add(sizes.stream().sorted().map(bucket -> "(" + bucket.predicate() + ")")
                    .collect(Collectors.joining(" OR ", "(", ")")));
        }
        return new Sql(String.join(" AND ", clauses), params);
    }

    private static List<String> nonBlank(List<String> values) {
        return values == null ? List.of() : values.stream().filter(value -> !isBlank(value)).toList();
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
