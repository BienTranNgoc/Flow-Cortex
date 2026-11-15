package com.flowcortex.workflow.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The one grammar and analysis of a step's {@code input_ref}: {@code {step:HEAD}} or
 * {@code {step:HEAD.PATH}}, HEAD a step order (digits) or an exact step name, PATH dotted fields
 * with optional {@code [n]} indexes. Pure: no I/O, no persistence shape.
 */
final class StepReferences {

    /** Length of {@code input_ref}'s column; longer text can never have been stored. */
    private static final int MAX_LENGTH = 255;

    private static final Pattern REFERENCE =
            Pattern.compile("\\{step:([^.\\[\\]{}]+)((?:\\.\\w+(?:\\[\\d+])*)*)}");
    private static final Pattern SEGMENT = Pattern.compile("\\.(\\w+)((?:\\[\\d+])*)");
    private static final Pattern INDEX = Pattern.compile("\\[(\\d+)]");
    private static final Pattern DIGITS = Pattern.compile("\\d+");

    private StepReferences() {}

    sealed interface Head permits Order, Name {}

    /** A positional head; {@code text} is the digits as written, kept for over-long values. */
    record Order(int value, String text) implements Head {
        static Order of(int value) {
            return new Order(value, Integer.toString(value));
        }
    }

    record Name(String value) implements Head {}

    sealed interface Segment permits Field, Index {}

    record Field(String name) implements Segment {}

    record Index(int position) implements Segment {}

    record Reference(Head head, List<Segment> path) {}

    record Step(UUID id, int order, String name, String inputRef) {}

    enum Kind {
        MALFORMED,
        UNKNOWN_STEP,
        AMBIGUOUS_NAME,
        NOT_EARLIER
    }

    /** {@code detail} is the head as written (order digits or name), or the whole text when malformed. */
    record Problem(Kind kind, String detail) {}

    static Optional<Reference> parse(String inputRef) {
        if (inputRef == null || inputRef.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        Matcher matcher = REFERENCE.matcher(inputRef);
        if (!matcher.matches()) {
            return Optional.empty();
        }
        String head = matcher.group(1);
        if (!head.equals(head.strip())) {
            return Optional.empty();
        }
        List<Segment> path = new ArrayList<>();
        Matcher segments = SEGMENT.matcher(matcher.group(2));
        while (segments.find()) {
            path.add(new Field(segments.group(1)));
            Matcher indexes = INDEX.matcher(segments.group(2));
            while (indexes.find()) {
                Integer position = toInt(indexes.group(1));
                if (position == null) {
                    return Optional.empty();
                }
                path.add(new Index(position));
            }
        }
        if (DIGITS.matcher(head).matches()) {
            Integer order = toInt(head);
            return Optional.of(new Reference(new Order(order == null ? 0 : order, head), path));
        }
        return Optional.of(new Reference(new Name(head), path));
    }

    /** One problem per step with a reference, the first kind of {@link Kind}'s declaration order. */
    static Map<UUID, Problem> analyze(List<Step> steps) {
        Map<Integer, Integer> orders = new HashMap<>();
        Map<String, List<Step>> byName = new HashMap<>();
        for (Step step : steps) {
            orders.put(step.order(), step.order());
            if (step.name() != null) {
                byName.computeIfAbsent(step.name(), key -> new ArrayList<>()).add(step);
            }
        }
        Map<UUID, Problem> problems = new LinkedHashMap<>();
        for (Step step : steps) {
            Problem problem = problemOf(step, orders, byName);
            if (problem != null) {
                problems.put(step.id(), problem);
            }
        }
        return problems;
    }

    /** The order of the step {@code step} reads, empty when it has no reference or the reference is broken. */
    static OptionalInt resolve(Step step, List<Step> steps) {
        Map<UUID, Problem> problems = analyze(steps);
        if (problems.containsKey(step.id()) || step.inputRef() == null || step.inputRef().isBlank()) {
            return OptionalInt.empty();
        }
        Reference reference = parse(step.inputRef()).orElseThrow();
        return switch (reference.head()) {
            case Order order -> OptionalInt.of(order.value());
            case Name name -> OptionalInt.of(uniqueOrder(name.value(), steps).orElseThrow());
        };
    }

    /** Turns a by-name reference into the positional one of the uniquely named step; anything else is unchanged. */
    static String canonicalize(String inputRef, List<Step> steps) {
        Optional<Reference> parsed = parse(inputRef);
        if (parsed.isEmpty() || !(parsed.get().head() instanceof Name name)) {
            return inputRef;
        }
        OptionalInt order = uniqueOrder(name.value(), steps);
        return order.isEmpty() ? inputRef : format(Order.of(order.getAsInt()), parsed.get().path());
    }

    static String format(Head head, List<Segment> path) {
        StringBuilder text = new StringBuilder("{step:");
        text.append(
                switch (head) {
                    case Order order -> order.text();
                    case Name name -> name.value();
                });
        for (Segment segment : path) {
            switch (segment) {
                case Field field -> text.append('.').append(field.name());
                case Index index -> text.append('[').append(index.position()).append(']');
            }
        }
        return text.append('}').toString();
    }

    private static Problem problemOf(Step step, Map<Integer, Integer> orders, Map<String, List<Step>> byName) {
        String text = step.inputRef();
        if (text == null || text.isBlank()) {
            return null;
        }
        Optional<Reference> parsed = parse(text);
        if (parsed.isEmpty()) {
            return new Problem(Kind.MALFORMED, text);
        }
        switch (parsed.get().head()) {
            case Order order -> {
                if (!orders.containsKey(order.value())) {
                    return new Problem(Kind.UNKNOWN_STEP, order.text());
                }
                return order.value() >= step.order() ? new Problem(Kind.NOT_EARLIER, order.text()) : null;
            }
            case Name name -> {
                List<Step> named = byName.getOrDefault(name.value(), List.of());
                if (named.isEmpty()) {
                    return new Problem(Kind.UNKNOWN_STEP, name.value());
                }
                if (named.size() > 1) {
                    return new Problem(Kind.AMBIGUOUS_NAME, name.value());
                }
                return named.get(0).order() >= step.order() ? new Problem(Kind.NOT_EARLIER, name.value()) : null;
            }
        }
    }

    private static OptionalInt uniqueOrder(String name, List<Step> steps) {
        OptionalInt found = OptionalInt.empty();
        for (Step candidate : steps) {
            if (name.equals(candidate.name())) {
                if (found.isPresent()) {
                    return OptionalInt.empty();
                }
                found = OptionalInt.of(candidate.order());
            }
        }
        return found;
    }

    private static Integer toInt(String digits) {
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
