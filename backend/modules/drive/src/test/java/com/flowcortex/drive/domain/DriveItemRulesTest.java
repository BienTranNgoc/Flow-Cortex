package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.DriveItemRules.CreateInput;
import com.flowcortex.drive.domain.DriveItemRules.UpdateInput;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Create and update rules of a Drive item: one verdict per field, never an exception. */
class DriveItemRulesTest {

    private static CreateInput text(String name, String role, String text) {
        return new CreateInput(name, "text", role, text, false, false);
    }

    private static CreateInput media(String type, String role, boolean upload, boolean runOutput, String text) {
        return new CreateInput("Logo", type, role, text, upload, runOutput);
    }

    @Test
    void aNameIsTrimmedAndMustHave1To160Characters() {
        assertThat(DriveItemRules.normalizeName("  New logo  ")).isEqualTo("New logo");
        assertThat(DriveItemRules.normalizeName(null)).isNull();
        assertThat(DriveItemRules.validateCreate(text("x".repeat(160), "company_bio", "hi"))).isEmpty();
        assertThat(DriveItemRules.validateCreate(text(" x ", "company_bio", "hi"))).isEmpty();
        assertThat(DriveItemRules.validateCreate(text("x".repeat(161), "company_bio", "hi")))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
        assertThat(DriveItemRules.validateCreate(text("   ", "company_bio", "hi")))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
        assertThat(DriveItemRules.validateCreate(text(null, "company_bio", "hi")))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
        assertThat(DriveItemRules.validateCreate(media("image", "logo", true, false, null))).isEmpty();
        assertThat(DriveItemRules.validateCreate(new CreateInput("   ", "image", "logo", null, true, false)))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput("   ", null, null)))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput("x".repeat(161), null, null)))
                .containsExactly(Map.entry("name", "drive.name.invalid"));
    }

    @Test
    void aTextOf32768IsAcceptedAnd32769Refused() {
        assertThat(DriveItemRules.validateCreate(text("Bio", "company_bio", "a".repeat(32768)))).isEmpty();
        assertThat(DriveItemRules.validateCreate(text("Bio", "company_bio", "a".repeat(32769))))
                .containsExactly(Map.entry("textValue", "drive.text.invalid"));
        assertThat(DriveItemRules.validateCreate(text("Bio", "company_bio", "")))
                .containsExactly(Map.entry("textValue", "drive.text.invalid"));
        assertThat(DriveItemRules.validateCreate(text("Bio", "company_bio", null)))
                .containsExactly(Map.entry("textValue", "drive.text.invalid"));
        assertThat(DriveItemRules.validateUpdate("text", new UpdateInput(null, null, "a".repeat(32769))))
                .containsExactly(Map.entry("textValue", "drive.text.invalid"));
        assertThat(DriveItemRules.validateUpdate("text", new UpdateInput(null, null, "a".repeat(32768)))).isEmpty();
    }

    @Test
    void exactlyOneOfTextUploadOrRunOutputIsAccepted() {
        assertThat(DriveItemRules.validateCreate(text("Bio", "company_bio", "hi"))).isEmpty();
        assertThat(DriveItemRules.validateCreate(media("image", "logo", true, false, null))).isEmpty();
        assertThat(DriveItemRules.validateCreate(media("image", "logo", false, true, null))).isEmpty();

        Map<String, String> payload = Map.of("payload", "drive.payload.invalid");
        assertThat(DriveItemRules.validateCreate(media("image", "logo", false, false, null))).isEqualTo(payload);
        assertThat(DriveItemRules.validateCreate(media("image", "logo", true, true, null))).isEqualTo(payload);
        assertThat(DriveItemRules.validateCreate(media("image", "logo", true, false, "x"))).isEqualTo(payload);
        assertThat(DriveItemRules.validateCreate(media("image", "logo", false, false, "x"))).isEqualTo(payload);
        assertThat(DriveItemRules.validateCreate(new CreateInput("Bio", "text", "company_bio", "hi", true, false)))
                .isEqualTo(payload);
        assertThat(DriveItemRules.validateCreate(new CreateInput("Bio", "text", "company_bio", "hi", false, true)))
                .isEqualTo(payload);
    }

    @Test
    void aRoleOfAnotherContentTypeIsRefusedOnSemanticRole() {
        assertThat(DriveItemRules.validateCreate(media("image", "company_bio", true, false, null)))
                .containsExactly(Map.entry("semanticRole", "drive.role.mismatch"));
        assertThat(DriveItemRules.validateCreate(text("Bio", "logo", "hi")))
                .containsExactly(Map.entry("semanticRole", "drive.role.mismatch"));
        assertThat(DriveItemRules.validateCreate(media("image", "selfie", true, false, null)))
                .containsExactly(Map.entry("semanticRole", "drive.value.invalid"));
        assertThat(DriveItemRules.validateCreate(media("image", null, true, false, null)))
                .containsExactly(Map.entry("semanticRole", "drive.value.invalid"));
        assertThat(DriveItemRules.validateCreate(media("document", "logo", true, false, null)))
                .containsExactly(Map.entry("contentType", "drive.value.invalid"));
        assertThat(DriveItemRules.validateCreate(media(null, "logo", true, false, null)))
                .containsExactly(Map.entry("contentType", "drive.value.invalid"));
        assertThat(DriveItemRules.validateCreate(media("audio", "audio_asset", true, false, null))).isEmpty();
        assertThat(DriveItemRules.validateCreate(media("video", "driver_video", false, true, null))).isEmpty();
    }

    @Test
    void anUpdateMayChangeTheRoleOnlyWithinTheContentType() {
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput(null, "product_photo", null))).isEmpty();
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput(null, "driver_video", null)))
                .containsExactly(Map.entry("semanticRole", "drive.role.mismatch"));
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput(null, "selfie", null)))
                .containsExactly(Map.entry("semanticRole", "drive.value.invalid"));
        assertThat(DriveItemRules.validateUpdate("text", new UpdateInput(null, "audience_note", null))).isEmpty();
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput(null, null, null))).isEmpty();
    }

    @Test
    void anUpdateOfTheTextOfAMediaItemIsRefused() {
        assertThat(DriveItemRules.validateUpdate("image", new UpdateInput(null, null, "hello")))
                .containsExactly(Map.entry("textValue", "drive.payload.invalid"));
        assertThat(DriveItemRules.validateUpdate("video", new UpdateInput("Clip", null, "hello")))
                .containsExactly(Map.entry("textValue", "drive.payload.invalid"));
    }
}
