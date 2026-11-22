package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PurgeOrderTest {

    private static final Map<String, Integer> RANKS = Map.of("demo", 10, "demo_late", 20);

    private record Item(String type, String id) {
    }

    @Test
    void lowerRankFirstThenRequestPosition() {
        List<Item> request = List.of(
                new Item("demo_late", "2"), new Item("demo", "1"), new Item("demo_late", "3"), new Item("demo", "4"));

        List<Item> ordered = PurgeOrder.byRank(request, item -> RANKS.get(item.type()));

        assertThat(ordered).extracting(Item::id).containsExactly("1", "4", "2", "3");
    }

    @Test
    void reportOrderIsRequestOrder() {
        List<Item> request = List.of(new Item("demo_late", "2"), new Item("demo", "1"));

        List<Item> processing = PurgeOrder.byRank(request, item -> RANKS.get(item.type()));

        assertThat(processing).extracting(Item::id).containsExactly("1", "2");
        assertThat(request).extracting(Item::id).containsExactly("2", "1");
    }
}
