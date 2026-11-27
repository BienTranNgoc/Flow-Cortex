package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetFileNamesTest {

    private static final UUID ID = UUID.fromString("0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70");

    @Test
    void titlePlusMimeExtension() {
        assertThat(AssetFileNames.fileName(ID, "Áo thun cotton trắng · Product video", "video/mp4"))
                .isEqualTo("Áo thun cotton trắng · Product video.mp4");
        assertThat(AssetFileNames.fileName(ID, "Cover", "image/jpeg")).isEqualTo("Cover.jpg");
        assertThat(AssetFileNames.fileName(ID, "Voice", "audio/mpeg")).isEqualTo("Voice.mp3");
        assertThat(AssetFileNames.fileName(ID, "Clip", "VIDEO/MP4; codecs=avc1")).isEqualTo("Clip.mp4");
    }

    @Test
    void extensionNotRepeatedWhenTitleEndsWithIt() {
        assertThat(AssetFileNames.fileName(ID, "ao-thun.jpg", "image/jpeg")).isEqualTo("ao-thun.jpg");
        assertThat(AssetFileNames.fileName(ID, "Clip.MP4", "video/mp4")).isEqualTo("Clip.MP4");
        assertThat(AssetFileNames.fileName(ID, "photo.jpeg", "image/jpeg")).isEqualTo("photo.jpeg");
        assertThat(AssetFileNames.fileName(ID, "Clip.mp4 final", "video/mp4")).isEqualTo("Clip.mp4 final.mp4");
    }

    @Test
    void dropsControlQuoteBackslashAndSlash() {
        assertThat(AssetFileNames.fileName(ID, "My \"best\"\nclip", "video/mp4")).isEqualTo("My bestclip.mp4");
        assertThat(AssetFileNames.fileName(ID, "a/b\\c\u0000d\te f‮g", "video/mp4")).isEqualTo("abcdefg.mp4");
        assertThat(AssetFileNames.fileName(ID, "../../etc/passwd", "image/png")).isEqualTo("....etcpasswd.png");
    }

    @Test
    void blankAfterCleaningFallsBackToId() {
        assertThat(AssetFileNames.fileName(ID, "\"\"/\\\n", "video/mp4")).isEqualTo(ID + ".mp4");
        assertThat(AssetFileNames.fileName(ID, "   ", "image/png")).isEqualTo(ID + ".png");
        assertThat(AssetFileNames.fileName(ID, null, "image/png")).isEqualTo(ID + ".png");
        assertThat(AssetFileNames.fileName(ID, ".mp4", "video/mp4")).isEqualTo(ID + ".mp4");
    }

    @Test
    void cutAt200CodePointsBeforeTheExtension() {
        assertThat(AssetFileNames.fileName(ID, "a".repeat(250), "video/mp4")).isEqualTo("a".repeat(200) + ".mp4");
        assertThat(AssetFileNames.fileName(ID, "a".repeat(250) + ".mp4", "video/mp4")).isEqualTo("a".repeat(200) + ".mp4");
        assertThat(AssetFileNames.fileName(ID, "a".repeat(200), "video/mp4")).isEqualTo("a".repeat(200) + ".mp4");

        String emoji = "😀";
        String cut = AssetFileNames.fileName(ID, emoji.repeat(201), "video/mp4");
        assertThat(cut).isEqualTo(emoji.repeat(200) + ".mp4");
    }

    @Test
    void unknownMimeHasNoExtension() {
        assertThat(AssetFileNames.fileName(ID, "Report", "application/x-unknown")).isEqualTo("Report");
        assertThat(AssetFileNames.fileName(ID, "Report", null)).isEqualTo("Report");
        assertThat(AssetFileNames.fileName(ID, "a".repeat(250), "application/x-unknown")).isEqualTo("a".repeat(200));
    }
}
