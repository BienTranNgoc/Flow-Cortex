package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.entity.domain.TagLabels.Tag;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Which spelling a tag keeps once the space already uses its key. */
class TagLabelsTest {

    @Test
    void existingKeyTakesTheSpacesLabel() {
        List<Tag> tags = TagLabels.resolve(List.of("mùa hè"), Map.of("mua he", "Mùa Hè"));

        assertThat(tags).containsExactly(new Tag("mua he", "Mùa Hè"));
    }

    @Test
    void newKeyKeepsTheTypedLabel() {
        List<Tag> tags = TagLabels.resolve(List.of("Sale", "mùa hè"), Map.of("mua he", "Mùa Hè"));

        assertThat(tags).containsExactly(new Tag("sale", "Sale"), new Tag("mua he", "Mùa Hè"));
    }

    @Test
    void aKeyTypedTwiceIsKeptOnceInFirstPosition() {
        List<Tag> tags = TagLabels.resolve(List.of("Sale", "SALE", "Tết"), Map.of());

        assertThat(tags).containsExactly(new Tag("sale", "Sale"), new Tag("tet", "Tết"));
    }

    @Test
    void noLabelsGiveNoTags() {
        assertThat(TagLabels.resolve(List.of(), Map.of())).isEmpty();
        assertThat(TagLabels.resolve(null, Map.of())).isEmpty();
    }
}
