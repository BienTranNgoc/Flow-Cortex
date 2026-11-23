package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PickOptionsTest {

    @Test
    void arrayOfTwoObjectsIsAList() {
        Optional<List<JsonNode>> options = PickOptions.detect("[{\"hook\":\"A\"},{\"hook\":\"B\"},{\"hook\":\"C\"}]");

        assertThat(options).isPresent();
        assertThat(options.get()).extracting(JsonNode::toString)
                .containsExactly("{\"hook\":\"A\"}", "{\"hook\":\"B\"}", "{\"hook\":\"C\"}");
    }

    @Test
    void wrappedSingleKeyArrayIsAList() {
        Optional<List<JsonNode>> options = PickOptions.detect("{\"ideas\":[{\"t\":\"x\"},{\"t\":\"y\"}]}");

        assertThat(options).isPresent();
        assertThat(options.get()).extracting(JsonNode::toString).containsExactly("{\"t\":\"x\"}", "{\"t\":\"y\"}");
    }

    @Test
    void oneObjectStringsNumbersTwoKeysOrTextAreNotLists() {
        assertThat(PickOptions.detect("[{\"t\":\"x\"}]")).isEmpty();
        assertThat(PickOptions.detect("[\"a\",\"b\"]")).isEmpty();
        assertThat(PickOptions.detect("[1,2]")).isEmpty();
        assertThat(PickOptions.detect("[{\"t\":\"x\"},\"b\"]")).isEmpty();
        assertThat(PickOptions.detect("{\"a\":[1,2],\"b\":[3,4]}")).isEmpty();
        assertThat(PickOptions.detect("{\"a\":[{\"t\":1},{\"t\":2}],\"b\":[{\"t\":3},{\"t\":4}]}")).isEmpty();
        assertThat(PickOptions.detect("{\"ideas\":\"not an array\"}")).isEmpty();
        assertThat(PickOptions.detect("{\"ideas\":[{\"t\":\"x\"}]}")).isEmpty();
        assertThat(PickOptions.detect("{\"hook\":\"A\"}")).isEmpty();
        assertThat(PickOptions.detect("42")).isEmpty();
        assertThat(PickOptions.detect("Draft post")).isEmpty();
        assertThat(PickOptions.detect("[{\"a\":1},{\"b\":2}] and more words")).isEmpty();
        assertThat(PickOptions.detect("")).isEmpty();
        assertThat(PickOptions.detect("   ")).isEmpty();
        assertThat(PickOptions.detect(null)).isEmpty();
    }

    @Test
    void forwardIsCompactJsonOfTheChosenOption() {
        List<JsonNode> options = PickOptions.detect("[ { \"hook\" : \"A\" },\n { \"hook\" : \"B\", \"n\": 2 } ]")
                .orElseThrow();

        assertThat(PickOptions.forward(options, 1)).isEqualTo("{\"hook\":\"B\",\"n\":2}");
        assertThat(PickOptions.forward(options, 0)).isEqualTo("{\"hook\":\"A\"}");
    }

    @Test
    void indexOutsideTheListIsRejected() {
        List<JsonNode> options = PickOptions.detect("[{\"hook\":\"A\"},{\"hook\":\"B\"}]").orElseThrow();

        assertThatThrownBy(() -> PickOptions.forward(options, 2)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PickOptions.forward(options, -1)).isInstanceOf(IllegalArgumentException.class);
    }
}
