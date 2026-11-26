package com.flowcortex.billing.profile;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BuyerNameTest {

    @Test
    void anEmojiIsRemovedFromTheEndOfAName() {
        assertThat(BuyerName.readable("Công ty ABC 🚀")).contains("Công ty ABC");
    }

    @Test
    void aNameOfEmojiOnlyHasNoReadableCharacter() {
        assertThat(BuyerName.readable("🎉")).isEmpty();
        assertThat(BuyerName.readable("🎉🚀")).isEmpty();
    }

    @Test
    void blankAndMissingNamesHaveNoReadableCharacter() {
        assertThat(BuyerName.readable("  ")).isEmpty();
        assertThat(BuyerName.readable(null)).isEmpty();
    }

    @Test
    void aZeroWidthJoinerIsRemovedAndWhitespaceCollapsed() {
        assertThat(BuyerName.readable("A‍ B")).contains("A B");
        assertThat(BuyerName.readable("  Nguyễn   Văn \t A ")).contains("Nguyễn Văn A");
    }
}
