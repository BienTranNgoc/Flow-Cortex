package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.text.Normalizer;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetNamingTest {

    private static final UUID RUN_ID = UUID.fromString("abcdef12-3456-7890-abcd-ef1234567890");

    @Test
    void shortInputIsTheFirstNonBlankLineNfcCollapsedAndCutTo60CodePoints() {
        String decomposed = Normalizer.normalize("Áo", Normalizer.Form.NFD);
        String base = AssetNaming.runTitleBase("\n  \n" + decomposed + "   thun \t trắng\nsecond line", "W", RUN_ID);
        assertThat(base).isEqualTo("Áo thun trắng · W");

        String longInput = "a".repeat(80);
        assertThat(AssetNaming.runTitleBase(longInput, "W", RUN_ID)).isEqualTo("a".repeat(60) + " · W");
    }

    @Test
    void baseIsShortInputDotWorkflowName() {
        assertThat(AssetNaming.runTitleBase("Áo thun cotton trắng", "Product video", RUN_ID))
                .isEqualTo("Áo thun cotton trắng · Product video");
    }

    @Test
    void baseWithoutInputIsTheWorkflowName() {
        assertThat(AssetNaming.runTitleBase(null, "Viết kịch bản", RUN_ID)).isEqualTo("Viết kịch bản");
        assertThat(AssetNaming.runTitleBase("  \n ", "Viết kịch bản", RUN_ID)).isEqualTo("Viết kịch bản");
    }

    @Test
    void baseWithInputButNoWorkflowNameIsTheShortInputAlone() {
        assertThat(AssetNaming.runTitleBase("Áo thun cotton trắng\nsize M", null, RUN_ID))
                .isEqualTo("Áo thun cotton trắng");
        assertThat(AssetNaming.runTitleBase("Áo thun", "  ", RUN_ID)).isEqualTo("Áo thun");
    }

    @Test
    void baseWithoutBothIsRunAndFirstEightHex() {
        assertThat(AssetNaming.runTitleBase(null, null, RUN_ID)).isEqualTo("Run abcdef12");
        assertThat(AssetNaming.runTitleBase("", " ", RUN_ID)).isEqualTo("Run abcdef12");
    }

    @Test
    void versionOneHasNoSuffix() {
        assertThat(AssetNaming.runTitle("Base", 1, 1, 1)).isEqualTo("Base");
    }

    @Test
    void versionTwoAddsSpaceParenTwo() {
        assertThat(AssetNaming.runTitle("Base", 2, 1, 1)).isEqualTo("Base (2)");
    }

    @Test
    void severalOutputsAddDotIndexInOrder() {
        assertThat(AssetNaming.runTitle("Base", 1, 1, 2)).isEqualTo("Base · 1");
        assertThat(AssetNaming.runTitle("Base", 1, 2, 2)).isEqualTo("Base · 2");
    }

    @Test
    void versionAndIndexComposeAsBaseParenNDotIndex() {
        assertThat(AssetNaming.runTitle("Base", 2, 1, 3)).isEqualTo("Base (2) · 1");
    }

    @Test
    void aSingleOutputHasNoIndex() {
        assertThat(AssetNaming.runTitle("Base", 3, 1, 1)).isEqualTo("Base (3)");
    }

    @Test
    void uploadWithOneBoundInputAndAFileNameIsTheName() {
        assertThat(AssetNaming.uploadTitle("ao-thun.jpg", 1, "Product video", "image_1")).isEqualTo("ao-thun.jpg");
    }

    @Test
    void uploadOtherwiseIsWorkflowNameDotBindingKey() {
        assertThat(AssetNaming.uploadTitle("a.jpg", 3, "Ghép ảnh", "image_2")).isEqualTo("Ghép ảnh · image_2");
        assertThat(AssetNaming.uploadTitle(null, 1, "Ghép ảnh", "image_1")).isEqualTo("Ghép ảnh · image_1");
        assertThat(AssetNaming.uploadTitle("  ", 1, "Ghép ảnh", "image_1")).isEqualTo("Ghép ảnh · image_1");
    }

    @Test
    void aProductVideoImageWithoutAFileNameIsWorkflowNameDotImage1() {
        assertThat(AssetNaming.uploadTitle(null, 1, "Product video", "image_1")).isEqualTo("Product video · image_1");
    }

    @Test
    void aProductNameTitleInputGivesProductNameDotWorkflowName() {
        assertThat(AssetNaming.runTitleBase("Áo thun cotton trắng", "Product video", RUN_ID))
                .isEqualTo("Áo thun cotton trắng · Product video");
    }

    @Test
    void aTitleIsAtMost200CodePointsAndTheCutKeepsTheSuffix() {
        String title = AssetNaming.runTitle("b".repeat(230), 3, 1, 1);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
        assertThat(title).endsWith(" (3)");

        String upload = AssetNaming.uploadTitle("f".repeat(250) + ".jpg", 1, "W", "k");
        assertThat(upload.codePointCount(0, upload.length())).isEqualTo(200);
    }

    @Test
    void cuttingNeverSplitsASurrogatePair() {
        String emoji = "😀";
        String title = AssetNaming.runTitle(emoji.repeat(230), 2, 1, 1);
        assertThat(title.codePointCount(0, title.length())).isEqualTo(200);
        assertThat(title).endsWith(emoji + " (2)");
        assertThat(title.chars().filter(c -> Character.isHighSurrogate((char) c)).count())
                .isEqualTo(title.chars().filter(c -> Character.isLowSurrogate((char) c)).count());
    }
}
