package com.flowcortex.workflow.application;

import java.text.Normalizer;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Pure rules for the marketplace read parameters. The {@code *Problem} checks return the reason a value is
 * refused, empty when it is fine; the web constraints turn a reason into the 400, so no exception is thrown here.
 */
public final class MarketplaceQuery {

    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SEARCH_LENGTH = 100;
    public static final int MAX_SEARCH_TERMS = 8;
    public static final int MAX_STEP_TYPES = 10;

    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    private MarketplaceQuery() {
    }

    /** The orders of the catalogue; {@code value()} is the wire name of the {@code sort} parameter. */
    public enum Sort {
        TRENDING("trending"),
        POPULAR("popular"),
        NEWEST("newest"),
        TOP_RATED("topRated"),
        NAME("name");

        private final String value;

        Sort(String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }

        /** An absent value is {@link #TRENDING}; any other value must match a wire name exactly. */
        public static Optional<Sort> parse(String value) {
            if (value == null) {
                return Optional.of(TRENDING);
            }
            return Arrays.stream(values()).filter(sort -> sort.value.equals(value)).findFirst();
        }
    }

    /** The parsed read: terms are NFC-normalized and already escaped for {@code LIKE ... ESCAPE '\'}. */
    public record Browse(List<String> terms, Set<String> stepTypes, Sort sort, int page, int size) {
    }

    public static Optional<String> searchProblem(String q) {
        String text = normalized(q);
        if (text.codePointCount(0, text.length()) > MAX_SEARCH_LENGTH) {
            return Optional.of("q is longer than " + MAX_SEARCH_LENGTH + " characters");
        }
        if (split(text).size() > MAX_SEARCH_TERMS) {
            return Optional.of("q has more than " + MAX_SEARCH_TERMS + " terms");
        }
        return Optional.empty();
    }

    public static Optional<String> stepTypesProblem(Collection<String> stepTypes, Set<String> knownTypes) {
        if (stepTypes == null) {
            return Optional.empty();
        }
        Set<String> distinct = new LinkedHashSet<>(stepTypes);
        if (distinct.size() > MAX_STEP_TYPES) {
            return Optional.of("more than " + MAX_STEP_TYPES + " distinct step types");
        }
        return distinct.stream()
                .filter(type -> type == null || !knownTypes.contains(type))
                .findFirst()
                .map(type -> "unknown step type");
    }

    public static Browse browse(String q, List<String> stepTypes, Sort sort, int page, int size) {
        List<String> terms = split(normalized(q)).stream().map(MarketplaceQuery::escapeLike).toList();
        Set<String> types = stepTypes == null
                ? Set.of()
                : Collections.unmodifiableSet(new LinkedHashSet<>(stepTypes));
        return new Browse(terms, types, sort == null ? Sort.TRENDING : sort, page, size);
    }

    private static String normalized(String q) {
        return q == null ? "" : Normalizer.normalize(q, Normalizer.Form.NFC).strip();
    }

    private static List<String> split(String text) {
        return Arrays.stream(WHITESPACE.split(text)).filter(term -> !term.isEmpty()).toList();
    }

    private static String escapeLike(String term) {
        return term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
