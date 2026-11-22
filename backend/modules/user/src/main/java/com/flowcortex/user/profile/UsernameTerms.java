package com.flowcortex.user.profile;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The username blocklist and allowlist, read once from the classpath data files. A malformed row
 * throws, so an unreadable list fails the first use instead of silently letting names through.
 */
final class UsernameTerms {

    private static final String BLOCKLIST = "/username-policy/blocklist.tsv";
    private static final String ALLOWLIST = "/username-policy/allowlist.txt";
    private static final Set<String> GROUPS = Set.of("R1", "R2", "R3", "R4", "B");
    private static final Set<String> MODES = Set.of("EXACT", "CONTAINS");

    /** One blocklist row; {@code mode} is EXACT (equals the normalised name) or CONTAINS. */
    record Entry(String group, String mode, String term) {}

    private final List<Entry> blocklist;
    private final List<String> allowlist;

    private UsernameTerms(List<Entry> blocklist, List<String> allowlist) {
        this.blocklist = blocklist;
        this.allowlist = allowlist;
    }

    static UsernameTerms load() {
        return new UsernameTerms(parseBlocklist(lines(BLOCKLIST)), parseAllowlist(lines(ALLOWLIST)));
    }

    List<Entry> blocklist() {
        return blocklist;
    }

    List<String> allowlist() {
        return allowlist;
    }

    static List<Entry> parseBlocklist(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (String line : data(lines)) {
            String[] cells = line.split("\t", -1);
            if (cells.length != 3) {
                throw new IllegalStateException("username blocklist: expected 3 columns in '" + line + "'");
            }
            if (!GROUPS.contains(cells[0])) {
                throw new IllegalStateException("username blocklist: unknown group in '" + line + "'");
            }
            if (!MODES.contains(cells[1])) {
                throw new IllegalStateException("username blocklist: unknown mode in '" + line + "'");
            }
            if (cells[2].isBlank()) {
                throw new IllegalStateException("username blocklist: empty term in '" + line + "'");
            }
            entries.add(new Entry(cells[0], cells[1], cells[2]));
        }
        if (entries.isEmpty()) {
            throw new IllegalStateException("username blocklist: no rows");
        }
        return List.copyOf(entries);
    }

    static List<String> parseAllowlist(List<String> lines) {
        return List.copyOf(data(lines));
    }

    private static List<String> data(List<String> lines) {
        return lines.stream()
                .map(String::strip)
                .filter(line -> !line.isEmpty() && !line.startsWith("#"))
                .toList();
    }

    private static List<String> lines(String resource) {
        try (InputStream in = UsernameTerms.class.getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("username policy data file missing: " + resource);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                return reader.lines().toList();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("username policy data file unreadable: " + resource, e);
        }
    }
}
