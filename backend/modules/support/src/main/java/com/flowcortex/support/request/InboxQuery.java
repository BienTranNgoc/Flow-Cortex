package com.flowcortex.support.request;

import com.flowcortex.shared.web.LocalizedMessage;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Turns the admin inbox's query parameters into one {@link InboxFilter}, or into a field error per refused
 * parameter. Pure: {@code now} and the caller come in, so the list, its total and every count read the same
 * filter.
 */
final class InboxQuery {

    static final int MAX_SEARCH_LENGTH = 100;

    private static final Pattern NUMBER = Pattern.compile("#?(\\d{1,18})");

    private InboxQuery() {
    }

    /** Exactly one of {@code filter} and {@code errors} is set. */
    record Parsed(InboxFilter filter, Map<String, LocalizedMessage> errors) {
    }

    /** {@code id} is null for the unassigned filter. */
    record AssigneeFilter(UUID id) {

        static final AssigneeFilter UNASSIGNED = new AssigneeFilter(null);

        static AssigneeFilter of(UUID id) {
            return new AssigneeFilter(id);
        }

        boolean unassigned() {
            return id == null;
        }
    }

    /**
     * @param pattern a LIKE pattern over the lower-cased subject, with the text's wildcards escaped
     * @param number the request number the text names, or null when it is not one
     */
    record Search(String pattern, Long number) {
    }

    /**
     * The creation-date range of a bucket: created after {@code after} (exclusive) and at or before
     * {@code atOrBefore} (inclusive); a null side is open.
     */
    record Bounds(Instant after, Instant atOrBefore) {
    }

    enum CreatedBucket {
        TODAY("today"), WEEK("7d"), MONTH("30d"), OLDER("older");

        private final String value;

        CreatedBucket(String value) {
            this.value = value;
        }

        String value() {
            return value;
        }

        static Optional<CreatedBucket> parse(String value) {
            return Arrays.stream(values()).filter(bucket -> bucket.value.equals(value)).findFirst();
        }

        /** The buckets tile the time line: each one starts where the next newer one ends. */
        Bounds bounds(Instant now) {
            Instant day = now.minus(Duration.ofDays(1));
            Instant week = now.minus(Duration.ofDays(7));
            Instant month = now.minus(Duration.ofDays(30));
            return switch (this) {
                case TODAY -> new Bounds(day, null);
                case WEEK -> new Bounds(week, day);
                case MONTH -> new Bounds(month, week);
                case OLDER -> new Bounds(null, month);
            };
        }
    }

    enum Sort {
        NEWEST("newest"), OLDEST("oldest");

        private final String value;

        Sort(String value) {
            this.value = value;
        }

        static Optional<Sort> parse(String value) {
            return Arrays.stream(values()).filter(sort -> sort.value.equals(value)).findFirst();
        }
    }

    /**
     * @param status null when not filtered
     * @param assignee null when not filtered
     * @param created null when not filtered
     * @param search null when there is no search text
     * @param now the one instant the created buckets are cut from
     */
    record InboxFilter(String status, String type, AssigneeFilter assignee, CreatedBucket created, Search search,
            Sort sort, int page, Instant now) {
    }

    static Parsed parse(String status, String type, String assignee, String created, String q, String sort,
            int page, UUID callerId, Instant now) {
        Map<String, LocalizedMessage> errors = new LinkedHashMap<>();
        String statusFilter = blankToNull(status);
        if (statusFilter != null && RequestStatus.parse(statusFilter).isEmpty()) {
            errors.put("status", new LocalizedMessage("support.request.error.statusUnknown"));
        }
        String typeFilter = blankToNull(type);
        if (typeFilter != null && !RequestService.TYPES.contains(typeFilter)) {
            errors.put("type", new LocalizedMessage("support.request.error.typeUnknown"));
        }
        AssigneeFilter assigneeFilter = assignee(blankToNull(assignee), callerId, errors);
        CreatedBucket bucket = null;
        String createdText = blankToNull(created);
        if (createdText != null) {
            bucket = CreatedBucket.parse(createdText).orElse(null);
            if (bucket == null) {
                errors.put("created", new LocalizedMessage("support.request.error.created"));
            }
        }
        Search search = search(q, errors);
        Sort order = Sort.NEWEST;
        String sortText = blankToNull(sort);
        if (sortText != null) {
            Optional<Sort> parsed = Sort.parse(sortText);
            if (parsed.isPresent()) {
                order = parsed.get();
            } else {
                errors.put("sort", new LocalizedMessage("support.request.error.sort"));
            }
        }
        if (page < 0 || page > RequestService.MAX_PAGE) {
            errors.put("page", new LocalizedMessage("support.request.error.page", RequestService.MAX_PAGE));
        }
        if (!errors.isEmpty()) {
            return new Parsed(null, errors);
        }
        return new Parsed(new InboxFilter(statusFilter, typeFilter, assigneeFilter, bucket, search, order, page, now),
                Map.of());
    }

    private static AssigneeFilter assignee(String value, UUID callerId, Map<String, LocalizedMessage> errors) {
        if (value == null) {
            return null;
        }
        if (value.equals("me")) {
            return AssigneeFilter.of(callerId);
        }
        if (value.equals("unassigned")) {
            return AssigneeFilter.UNASSIGNED;
        }
        try {
            return AssigneeFilter.of(UUID.fromString(value));
        } catch (IllegalArgumentException e) {
            errors.put("assignee", new LocalizedMessage("support.request.error.assignee"));
            return null;
        }
    }

    private static Search search(String q, Map<String, LocalizedMessage> errors) {
        if (q == null) {
            return null;
        }
        String text = q.strip();
        if (text.isEmpty() || text.codePointCount(0, text.length()) > MAX_SEARCH_LENGTH) {
            errors.put("q", new LocalizedMessage("support.request.error.q", MAX_SEARCH_LENGTH));
            return null;
        }
        var number = NUMBER.matcher(text);
        Long requestNumber = number.matches() ? Long.valueOf(number.group(1)) : null;
        return new Search("%" + escapeLike(text) + "%", requestNumber);
    }

    private static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
