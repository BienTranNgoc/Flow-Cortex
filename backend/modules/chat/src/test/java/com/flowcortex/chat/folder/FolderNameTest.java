package com.flowcortex.chat.folder;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class FolderNameTest {

    private static Map<String, LocalizedMessage> refused(String raw) {
        try {
            FolderName.parse(raw);
        } catch (ChatProblemException e) {
            assertThat(e.status()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(e.code()).isEqualTo("validation-failed");
            return e.fieldErrors();
        }
        throw new AssertionError("expected a refusal");
    }

    @Test
    void theNameIsTrimmed() {
        assertThat(FolderName.parse("  Ads  ").value()).isEqualTo("Ads");
    }

    @Test
    void aBlankOrMissingNameIsRefusedOnTheNameField() {
        for (String blank : new String[] {null, "", "   ", " \t\n "}) {
            Map<String, LocalizedMessage> errors = refused(blank);

            assertThat(errors).containsOnlyKeys("name");
            assertThat(errors.get("name").code()).isEqualTo("chat.folder.name.invalid");
        }
    }

    @Test
    void sixtyCodePointsAreAcceptedAndSixtyOneRefused() {
        assertThat(FolderName.parse("a".repeat(60)).value()).hasSize(60);

        assertThat(refused("a".repeat(61))).containsOnlyKeys("name");
    }

    @Test
    void theLengthIsMeasuredAfterTheTrim() {
        assertThat(FolderName.parse("  " + "a".repeat(60) + "  ").value()).hasSize(60);
    }

    @Test
    void aCharacterOutsideTheBasicPlaneCountsOnce() {
        String emoji = "😀";

        assertThat(FolderName.parse(emoji.repeat(60)).value().codePointCount(0, 120)).isEqualTo(60);
        assertThat(refused(emoji.repeat(61))).containsOnlyKeys("name");
    }
}
