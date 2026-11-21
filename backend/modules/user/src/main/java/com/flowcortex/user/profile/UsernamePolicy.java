package com.flowcortex.user.profile;

import com.flowcortex.user.profile.UsernameTerms.Entry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Pure username decisions, no I/O. {@link #decide} is an ordered rule list where the first rule that
 * answers wins, so precedence is the list order and nothing else; whether a name is taken or held
 * needs the database and is checked by the caller after a {@link Decision.Proceed}.
 */
final class UsernamePolicy {

    static final Duration COOLDOWN = Duration.ofDays(30);

    private static final Pattern RUN_OF_THREE_OR_MORE = Pattern.compile("(.)\\1{2,}");
    private static final Pattern TRAILING_DIGITS = Pattern.compile("\\d+$");
    private static final String PROFANITY_GROUP = "R3";

    sealed interface TextVerdict {
        record Ok() implements TextVerdict {}

        /** {@code group} is for logs only; it never reaches a response. */
        record Blocked(String group) implements TextVerdict {}
    }

    enum Refusal {
        CHANGE_TOO_SOON,
        NOT_ALLOWED
    }

    record Change(String current, String next, Instant changedAt, Instant now) {}

    sealed interface Decision {
        record Noop() implements Decision {}

        record Refuse(Refusal code, Instant nextChangeAt, String group) implements Decision {}

        record Proceed(boolean countsAsChange, boolean holdOld) implements Decision {}
    }

    private record Normalized(String s0, String s1) {}

    private record Level(int collapseTo, TermIndex reserved, Set<String> allowed, TermIndex profane) {}

    private record Term(String term, String group) {}

    private record TermIndex(Map<String, String> exact, List<Term> contains) {}

    private static final class Holder {
        static final Function<String, TextVerdict> DEFAULT = loadDefault();

        private static Function<String, TextVerdict> loadDefault() {
            UsernameTerms terms = UsernameTerms.load();
            return classifier(terms.blocklist(), terms.allowlist());
        }
    }

    private UsernamePolicy() {}

    static TextVerdict classify(String name) {
        return Holder.DEFAULT.apply(name);
    }

    /**
     * Matching order, first verdict wins: reserved groups (R1, R2, R4, B), which the allowlist never
     * exempts; the allowlist; then R3. Each step runs on the collapse-to-one and collapse-to-two forms
     * of the name against terms collapsed the same way, so a tripled letter cannot hide a double-letter
     * term. There is no leetspeak mapping.
     */
    static Function<String, TextVerdict> classifier(List<Entry> blocklist, List<String> allowlist) {
        List<Entry> reservedEntries = blocklist.stream().filter(e -> !PROFANITY_GROUP.equals(e.group())).toList();
        List<Entry> profaneEntries = blocklist.stream().filter(e -> PROFANITY_GROUP.equals(e.group())).toList();
        List<Level> levels = new ArrayList<>();
        for (int collapseTo : new int[] {1, 2}) {
            Set<String> allowed = allowlist.stream()
                    .map(term -> normalize(term, collapseTo).s0())
                    .collect(Collectors.toUnmodifiableSet());
            levels.add(new Level(collapseTo, index(reservedEntries, collapseTo), allowed, index(profaneEntries, collapseTo)));
        }
        return name -> {
            List<Normalized> forms = levels.stream().map(l -> normalize(name, l.collapseTo())).toList();
            for (int i = 0; i < levels.size(); i++) {
                Optional<String> group = match(levels.get(i).reserved(), forms.get(i));
                if (group.isPresent()) {
                    return new TextVerdict.Blocked(group.get());
                }
            }
            for (int i = 0; i < levels.size(); i++) {
                Set<String> allowed = levels.get(i).allowed();
                if (allowed.contains(forms.get(i).s0()) || allowed.contains(forms.get(i).s1())) {
                    return new TextVerdict.Ok();
                }
            }
            for (int i = 0; i < levels.size(); i++) {
                Optional<String> group = match(levels.get(i).profane(), forms.get(i));
                if (group.isPresent()) {
                    return new TextVerdict.Blocked(group.get());
                }
            }
            return new TextVerdict.Ok();
        };
    }

    static Decision decide(Change change) {
        for (Rule rule : RULES) {
            Optional<Decision> decision = rule.apply(change);
            if (decision.isPresent()) {
                return decision.get();
            }
        }
        throw new IllegalStateException("username policy: no rule matched");
    }

    private interface Rule {
        Optional<Decision> apply(Change change);
    }

    private static final List<Rule> RULES = List.of(
            // Re-sending the stored name is never evaluated.
            c -> c.current() != null && c.next().equalsIgnoreCase(c.current())
                    ? Optional.of(new Decision.Noop())
                    : Optional.empty(),
            // The cooldown answers before every other refusal.
            c -> {
                if (c.changedAt() == null) {
                    return Optional.empty();
                }
                Instant unlockAt = c.changedAt().plus(COOLDOWN);
                return c.now().isBefore(unlockAt)
                        ? Optional.of(new Decision.Refuse(Refusal.CHANGE_TOO_SOON, unlockAt, null))
                        : Optional.empty();
            },
            // One code for every group, so a refusal never tells which group fired.
            c -> classify(c.next()) instanceof TextVerdict.Blocked blocked
                    ? Optional.of(new Decision.Refuse(Refusal.NOT_ALLOWED, null, blocked.group()))
                    : Optional.empty(),
            c -> {
                boolean hadName = c.current() != null;
                return Optional.of(new Decision.Proceed(hadName, hadName));
            });

    private static Normalized normalize(String name, int collapseTo) {
        String s0 = RUN_OF_THREE_OR_MORE
                .matcher(name.toLowerCase(Locale.ROOT).replace("_", ""))
                .replaceAll(collapseTo == 1 ? "$1" : "$1$1");
        return new Normalized(s0, TRAILING_DIGITS.matcher(s0).replaceAll(""));
    }

    // Terms are normalised like names but keep their own trailing digits, so a term such as `www`
    // still meets the name `www`, which normalises to `w`.
    private static TermIndex index(List<Entry> entries, int collapseTo) {
        Map<String, String> exact = new HashMap<>();
        List<Term> contains = new ArrayList<>();
        for (Entry entry : entries) {
            String term = normalize(entry.term(), collapseTo).s0();
            if ("EXACT".equals(entry.mode())) {
                exact.put(term, entry.group());
            } else {
                contains.add(new Term(term, entry.group()));
            }
        }
        return new TermIndex(exact, contains);
    }

    private static Optional<String> match(TermIndex index, Normalized name) {
        String exact = index.exact().get(name.s0());
        if (exact != null) {
            return Optional.of(exact);
        }
        if (!name.s1().isEmpty()) {
            exact = index.exact().get(name.s1());
            if (exact != null) {
                return Optional.of(exact);
            }
        }
        return index.contains().stream()
                .filter(t -> name.s0().contains(t.term()))
                .map(Term::group)
                .findFirst();
    }
}
