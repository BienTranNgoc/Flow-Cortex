package com.flowcortex.asset.domain;

import com.flowcortex.asset.domain.AssetGroupKeys.GroupBy;
import com.flowcortex.asset.domain.AssetGroupKeys.GroupScope;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * The library's request parameters, validated. Every parameter is untrusted input on a member-data
 * path: an unknown value, a repeat, a number out of range or an unreadable date is refused naming the
 * parameter, and no parameter can select a space or a user (those never reach this class).
 */
public final class AssetQuery {

    public static final int DEFAULT_PAGE_SIZE = 48;
    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_GROUPS_PAGE_SIZE = 10;
    public static final int MAX_GROUPS_PAGE_SIZE = 30;
    public static final int DEFAULT_ITEMS_PER_GROUP = 4;
    public static final int MAX_ITEMS_PER_GROUP = 12;
    public static final int MAX_SEARCH_CODE_POINTS = 200;
    public static final int MAX_TAG_CODE_POINTS = 40;

    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    private static final Pattern DAY_TEXT = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern UUID_TEXT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private AssetQuery() {}

    /** Asset kinds, in the order the response lists them. */
    public enum Kind {
        VIDEO,
        IMAGE,
        AUDIO;

        /** The value stored in {@code space_asset.kind}, also the request value. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Where an asset came from. */
    public enum Source {
        RUN,
        UPLOAD;

        /** The value stored in {@code space_asset.source}, also the request value. */
        public String value() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** Page order; ties are broken by created time then id in the sort's direction. */
    public enum Sort {
        NEWEST("newest"),
        OLDEST("oldest"),
        SIZE_DESC("size_desc"),
        SIZE_ASC("size_asc");

        private final String value;

        Sort(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** Whether the page order starts with the byte size. */
        public boolean bySize() {
            return this == SIZE_DESC || this == SIZE_ASC;
        }
    }

    /** The time filter. */
    public enum TimeWindow {
        ALL("all"),
        LAST_7_DAYS("7d"),
        LAST_30_DAYS("30d"),
        CUSTOM("custom");

        private final String value;

        TimeWindow(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    /**
     * The filters the list and the groups share. {@code from} and {@code to} are Vietnam days, set
     * only for {@link TimeWindow#CUSTOM}; {@code q} and {@code tag} are NFC and whitespace-collapsed
     * but not folded (the database folds them); {@code group} is set only on the list.
     */
    public record Filters(
            Set<Kind> kinds,
            Set<Source> sources,
            String q,
            String tag,
            boolean favorite,
            TimeWindow time,
            LocalDate from,
            LocalDate to,
            GroupScope group,
            UUID runId) {

        public Filters {
            kinds = Collections.unmodifiableSet(kinds.isEmpty() ? EnumSet.noneOf(Kind.class) : EnumSet.copyOf(kinds));
            sources = Collections.unmodifiableSet(
                    sources.isEmpty() ? EnumSet.noneOf(Source.class) : EnumSet.copyOf(sources));
        }

        /** How far back from the mark a {@code 7d} or {@code 30d} window reaches; null otherwise. */
        public Duration lookback() {
            return switch (time) {
                case LAST_7_DAYS -> Duration.ofDays(7);
                case LAST_30_DAYS -> Duration.ofDays(30);
                default -> null;
            };
        }

        /** First instant of {@code from}; null unless the window is custom. */
        public Instant fromInstant() {
            return from == null ? null : from.atStartOfDay(AssetGroupKeys.ZONE).toInstant();
        }

        /** First instant after {@code to}'s day; null unless the window is custom. */
        public Instant toExclusiveInstant() {
            return to == null ? null : to.plusDays(1).atStartOfDay(AssetGroupKeys.ZONE).toInstant();
        }

        /**
         * The filter in a form independent of how the request spelled it: kinds and sources in enum
         * order, each value length-prefixed so no value can imitate another field.
         */
        public String canonical() {
            StringBuilder out = new StringBuilder();
            field(out, "kind", String.join(",", kinds.stream().map(Kind::value).toList()));
            field(out, "source", String.join(",", sources.stream().map(Source::value).toList()));
            field(out, "q", q);
            field(out, "tag", tag);
            field(out, "favorite", String.valueOf(favorite));
            field(out, "time", time.value());
            field(out, "from", from == null ? null : from.toString());
            field(out, "to", to == null ? null : to.toString());
            field(out, "group", group == null ? null : group.key());
            field(out, "runId", runId == null ? null : runId.toString());
            return out.toString();
        }

        private static void field(StringBuilder out, String name, String value) {
            out.append(name).append('=');
            if (value == null) {
                out.append('-');
            } else {
                out.append(value.length()).append(':').append(value);
            }
            out.append('\n');
        }
    }

    /** One page of the library list; {@code cursor} and {@code mark} are null when not sent. */
    public record ListQuery(Filters filters, Sort sort, int pageSize, String cursor, Instant mark) {

        public static ListQuery parse(Map<String, List<String>> params, Instant now) {
            Params in = new Params(params);
            Filters filters = readFilters(in, true);
            Sort sort = in.enumValue("sort", Sort.NEWEST, Sort.values(), Sort::value);
            int pageSize = in.integer("pageSize", DEFAULT_PAGE_SIZE, 1, MAX_PAGE_SIZE);
            String cursor = in.single("cursor");
            return new ListQuery(filters, sort, pageSize, cursor, readMark(in, now));
        }
    }

    /** One page of groups; {@code mark} is null on the first page when not sent. */
    public record GroupsQuery(
            Filters filters, GroupBy groupBy, Sort sort, int page, int pageSize, int itemsPerGroup, Instant mark) {

        public static GroupsQuery parse(Map<String, List<String>> params, Instant now) {
            Params in = new Params(params);
            Filters filters = readFilters(in, false);
            GroupBy groupBy = in.enumValue("groupBy", GroupBy.WORKFLOW, GroupBy.values(), GroupBy::value);
            Sort sort = in.enumValue("sort", Sort.NEWEST, Sort.values(), Sort::value);
            int page = in.integer("page", 1, 1, Integer.MAX_VALUE);
            int pageSize = in.integer("pageSize", DEFAULT_GROUPS_PAGE_SIZE, 1, MAX_GROUPS_PAGE_SIZE);
            int itemsPerGroup = in.integer("itemsPerGroup", DEFAULT_ITEMS_PER_GROUP, 1, MAX_ITEMS_PER_GROUP);
            return new GroupsQuery(filters, groupBy, sort, page, pageSize, itemsPerGroup, readMark(in, now));
        }
    }

    /**
     * Escapes {@code %}, {@code _} and {@code \} so the text matches literally inside a
     * {@code LIKE … ESCAPE '\'} pattern; the caller adds the surrounding {@code %}.
     */
    public static String likePattern(String text) {
        StringBuilder out = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '%' || c == '_' || c == '\\') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static Filters readFilters(Params in, boolean withGroup) {
        Set<Kind> kinds = in.enumSet("kind", Kind.class, Kind.values(), Kind::value);
        Set<Source> sources = in.enumSet("source", Source.class, Source.values(), Source::value);
        String q = in.text("q", MAX_SEARCH_CODE_POINTS);
        String tag = in.text("tag", MAX_TAG_CODE_POINTS);
        boolean favorite = in.bool("favorite");
        TimeWindow time = in.enumValue("time", TimeWindow.ALL, TimeWindow.values(), TimeWindow::value);
        LocalDate from = in.day("from");
        LocalDate to = in.day("to");
        if (time == TimeWindow.CUSTOM) {
            if (from == null) {
                throw new AssetRequestRejected("from");
            }
            if (to == null) {
                throw new AssetRequestRejected("to");
            }
            if (from.isAfter(to)) {
                throw new AssetRequestRejected("from");
            }
        } else {
            from = null;
            to = null;
        }
        GroupScope group = null;
        if (withGroup) {
            String key = in.single("group");
            group = key == null ? null : AssetGroupKeys.parse(key);
        }
        String runId = in.single("runId");
        if (runId != null && !UUID_TEXT.matcher(runId).matches()) {
            throw new AssetRequestRejected("runId");
        }
        return new Filters(kinds, sources, q, tag, favorite, time, from, to, group,
                runId == null ? null : UUID.fromString(runId));
    }

    private static Instant readMark(Params in, Instant now) {
        String value = in.single("mark");
        if (value == null) {
            return null;
        }
        Instant mark;
        try {
            mark = Instant.parse(value);
        } catch (DateTimeParseException e) {
            throw new AssetRequestRejected("mark");
        }
        if (mark.isAfter(now)) {
            throw new AssetRequestRejected("mark");
        }
        return mark;
    }

    /** Reads one request's parameters: blank counts as absent, a repeat is refused. */
    private static final class Params {

        private final Map<String, List<String>> values;

        Params(Map<String, List<String>> values) {
            this.values = values;
        }

        String single(String name) {
            List<String> given = values.get(name);
            if (given == null || given.isEmpty()) {
                return null;
            }
            if (given.size() > 1) {
                throw new AssetRequestRejected(name);
            }
            String value = given.get(0);
            return value == null || value.isBlank() ? null : value.strip();
        }

        <E extends Enum<E>> E enumValue(String name, E fallback, E[] all, Function<E, String> wire) {
            String value = single(name);
            return value == null ? fallback : lookup(name, value, all, wire);
        }

        <E extends Enum<E>> Set<E> enumSet(String name, Class<E> type, E[] all, Function<E, String> wire) {
            String value = single(name);
            Set<E> found = EnumSet.noneOf(type);
            if (value == null) {
                return found;
            }
            for (String item : value.split(",", -1)) {
                String trimmed = item.strip();
                if (!trimmed.isEmpty()) {
                    found.add(lookup(name, trimmed, all, wire));
                }
            }
            return found;
        }

        int integer(String name, int fallback, int min, int max) {
            String value = single(name);
            if (value == null) {
                return fallback;
            }
            int parsed;
            try {
                parsed = Integer.parseInt(value);
            } catch (NumberFormatException e) {
                throw new AssetRequestRejected(name);
            }
            if (parsed < min || parsed > max) {
                throw new AssetRequestRejected(name);
            }
            return parsed;
        }

        boolean bool(String name) {
            String value = single(name);
            if (value == null || value.equals("false")) {
                return false;
            }
            if (value.equals("true")) {
                return true;
            }
            throw new AssetRequestRejected(name);
        }

        LocalDate day(String name) {
            String value = single(name);
            if (value == null) {
                return null;
            }
            if (!DAY_TEXT.matcher(value).matches()) {
                throw new AssetRequestRejected(name);
            }
            try {
                return LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                throw new AssetRequestRejected(name);
            }
        }

        /** NFC, whitespace-collapsed text; blank is null; over {@code maxCodePoints} is refused. */
        String text(String name, int maxCodePoints) {
            String value = single(name);
            if (value == null) {
                return null;
            }
            String normalized =
                    WHITESPACE.matcher(Normalizer.normalize(value, Normalizer.Form.NFC)).replaceAll(" ").strip();
            if (normalized.isEmpty()) {
                return null;
            }
            if (normalized.codePointCount(0, normalized.length()) > maxCodePoints) {
                throw new AssetRequestRejected(name);
            }
            return normalized;
        }

        private static <E extends Enum<E>> E lookup(String name, String value, E[] all, Function<E, String> wire) {
            for (E candidate : all) {
                if (wire.apply(candidate).equals(value)) {
                    return candidate;
                }
            }
            throw new AssetRequestRejected(name);
        }
    }
}
