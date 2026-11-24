package com.flowcortex.trash.domain;

import java.util.Comparator;
import java.util.List;
import java.util.function.Function;
import java.util.function.ToIntFunction;

/** The order permanent deletes run in. */
public final class PurgeOrder {

    private PurgeOrder() {
    }

    /**
     * A copy ordered by ascending rank, then type key; items of one type and rank keep their request position (the
     * sort is stable).
     */
    public static <T> List<T> byRank(List<T> items, ToIntFunction<T> rank, Function<T, String> type) {
        return items.stream().sorted(Comparator.comparingInt(rank).thenComparing(type)).toList();
    }
}
