package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.trash.domain.TrashRequestRules.ItemRef;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class TrashRequestRulesTest {

    @Test
    void emptyItemsIsInvalid() {
        assertThat(TrashRequestRules.checkItems(List.of())).contains("items");
        assertThat(TrashRequestRules.checkItems(null)).contains("items");
    }

    @Test
    void oneHundredOneItemsIsInvalid() {
        assertThat(TrashRequestRules.checkItems(items(101))).contains("items");
    }

    @Test
    void aRepeatedPairIsInvalid() {
        assertThat(TrashRequestRules.checkItems(List.of(new ItemRef("demo", "1"), new ItemRef("demo", "1"))))
                .contains("items");
        assertThat(TrashRequestRules.checkItems(List.of(new ItemRef("demo", "1"), new ItemRef("demo_late", "1"))))
                .isEmpty();
    }

    @Test
    void aMalformedIdIsInvalid() {
        assertThat(TrashRequestRules.checkItems(List.of(new ItemRef("demo", "a b")))).contains("items");
        assertThat(TrashRequestRules.checkItems(List.of(new ItemRef("Demo", "1")))).contains("items");
        assertThat(TrashRequestRules.checkItems(List.of(new ItemRef("demo", null)))).contains("items");
        assertThat(TrashRequestRules.checkItems(Arrays.asList(new ItemRef("demo", "1"), null))).contains("items");
    }

    @Test
    void oneHundredDistinctItemsIsValid() {
        assertThat(TrashRequestRules.checkItems(items(100))).isEmpty();
    }

    private static List<ItemRef> items(int count) {
        List<ItemRef> items = new ArrayList<>();
        IntStream.range(0, count).forEach(i -> items.add(new ItemRef("demo", "id" + i)));
        return items;
    }
}
