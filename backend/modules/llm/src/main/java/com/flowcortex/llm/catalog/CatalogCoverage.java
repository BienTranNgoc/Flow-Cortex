package com.flowcortex.llm.catalog;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

/** Compares the capability rows with the capabilities the engine declared. Pure. */
final class CatalogCoverage {

    private CatalogCoverage() {
    }

    /**
     * {@code registered} holds the row keys the engine declared; {@code uncatalogued} the declared keys with no
     * row, sorted.
     */
    record Coverage(Set<String> registered, List<String> uncatalogued) {

        boolean isRegistered(String capability) {
            return registered.contains(capability);
        }
    }

    static Coverage assemble(Collection<String> rowKeys, Set<String> declared) {
        Set<String> rows = new HashSet<>(rowKeys);
        Set<String> registered = new HashSet<>(rows);
        registered.retainAll(declared);
        Set<String> missing = new TreeSet<>(declared);
        missing.removeAll(rows);
        return new Coverage(Set.copyOf(registered), List.copyOf(missing));
    }
}
