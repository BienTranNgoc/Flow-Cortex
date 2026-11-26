package com.flowcortex.connector.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

class DriveFolderIdTest {

    @Test
    void takesTheIdOfAFolderLinkWithItsQueryString() {
        assertThat(DriveFolderId.parse("https://drive.google.com/drive/folders/1AbCdEfGhIjK?usp=sharing"))
                .contains("1AbCdEfGhIjK");
    }

    @Test
    void takesTheIdOfAFolderLinkInAnAccountSegmentWithDashAndUnderscore() {
        assertThat(DriveFolderId.parse("https://drive.google.com/drive/u/0/folders/1AbC-d_EfGhI"))
                .contains("1AbC-d_EfGhI");
    }

    @Test
    void aBareIdIsItself() {
        assertThat(DriveFolderId.parse("1AbCdEfGhIjK")).contains("1AbCdEfGhIjK");
    }

    @Test
    void surroundingBlanksAreTrimmed() {
        assertThat(DriveFolderId.parse(" 1AbCdEfGhIjK ")).contains("1AbCdEfGhIjK");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "https://drive.google.com/file/d/1XyZ/view",
            "https://drive.google.com/drive/my-drive",
            "see attached file",
            "short",
            "   ",
            "https://drive.google.com/drive/folders/1AbCdEfGhIjK!x"
    })
    @NullAndEmptySource
    void anythingElseHasNoId(String value) {
        assertThat(DriveFolderId.parse(value)).isEmpty();
    }

    @Test
    void aValueOver2048CharactersHasNoId() {
        assertThat(DriveFolderId.parse("a".repeat(2049))).isEmpty();
        assertThat(DriveFolderId.parse("a".repeat(2048))).isPresent();
    }
}
