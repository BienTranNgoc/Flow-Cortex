package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurgeOrderTest {

    private static final Map<String, Integer> RANKS = Map.of("demo", 10, "zeta", 10, "demo_late", 20);

    private record Item(String type, String id) {
    }

    private static List<Item> byRank(List<Item> request) {
        return PurgeOrder.byRank(request, item -> RANKS.get(item.type()), Item::type);
    }

    @Test
    void lowerRankFirstThenRequestPosition() {
        List<Item> request = List.of(
                new Item("demo_late", "2"), new Item("demo", "1"), new Item("demo_late", "3"), new Item("demo", "4"));

        List<Item> ordered = byRank(request);

        assertThat(ordered).extracting(Item::id).containsExactly("1", "4", "2", "3");
    }

    @Test
    void typesOfOneRankRunByTypeKeyThenRequestPosition() {
        List<Item> request = List.of(
                new Item("demo_late", "1"), new Item("zeta", "2"), new Item("demo", "3"), new Item("zeta", "4"),
                new Item("demo", "5"));

        List<Item> ordered = byRank(request);

        assertThat(ordered).extracting(Item::id).containsExactly("3", "5", "2", "4", "1");
    }

    @Test
    void reportOrderIsRequestOrder() {
        List<Item> request = List.of(new Item("demo_late", "2"), new Item("demo", "1"));

        List<Item> processing = byRank(request);

        assertThat(processing).extracting(Item::id).containsExactly("1", "2");
        assertThat(request).extracting(Item::id).containsExactly("2", "1");
    }
}
