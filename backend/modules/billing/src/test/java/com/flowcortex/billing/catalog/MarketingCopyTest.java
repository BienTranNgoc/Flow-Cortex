package com.flowcortex.billing.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class MarketingCopyTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static MarketingCopy read(String json) {
        return MarketingCopy.read(JSON.readTree(json));
    }

    @Test
    void bothListsAreRead() {
        MarketingCopy copy = read("{\"vi\":[\"a\",\"b\"],\"en\":[\"c\"]}");

        assertThat(copy.vi()).containsExactly("a", "b");
        assertThat(copy.en()).containsExactly("c");
    }

    @Test
    void aNonTextEntryIsDroppedAndAMissingListIsEmpty() {
        MarketingCopy copy = read("{\"vi\":[\"45.000 Credit/tháng\", 7, null, {\"x\":1}]}");

        assertThat(copy.vi()).containsExactly("45.000 Credit/tháng");
        assertThat(copy.en()).isEmpty();
    }

    @Test
    void aListThatIsNotAnArrayIsEmpty() {
        MarketingCopy copy = read("{\"vi\":\"x\",\"en\":{\"a\":\"b\"}}");

        assertThat(copy.vi()).isEmpty();
        assertThat(copy.en()).isEmpty();
    }

    @Test
    void aValueThatIsNotAnObjectGivesTwoEmptyLists() {
        for (String json : List.of("[\"a\"]", "null", "\"text\"", "7")) {
            assertThat(read(json)).isEqualTo(new MarketingCopy(List.of(), List.of()));
        }
    }

    @Test
    void aMissingValueGivesTwoEmptyLists() {
        assertThat(MarketingCopy.read((JsonNode) null)).isEqualTo(new MarketingCopy(List.of(), List.of()));
    }
}
