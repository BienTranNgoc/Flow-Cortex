package com.flowcortex.entity.infrastructure;

/** Builds {@code LIKE} patterns from member text, so {@code %}, {@code _} and {@code \} match themselves. */
final class SqlLike {

    private SqlLike() {
    }

    static String contains(String text) {
        return "%" + escape(text) + "%";
    }

    static String prefix(String text) {
        return escape(text) + "%";
    }

    /** A word prefix of a text key: the key starts with the text, or a later word does. */
    static String wordStart(String text) {
        return "% " + escape(text) + "%";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
