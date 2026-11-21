package com.flowcortex.drive.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

/** The role table: every role belongs to exactly one content type. */
class DriveRolesTest {

    @Test
    void everyRoleMapsToExactlyOneContentType() {
        assertThat(DriveRoles.all()).hasSize(11).doesNotHaveDuplicates();
        for (String role : DriveRoles.all()) {
            long owners = Arrays.stream(ContentType.values())
                    .filter(type -> DriveRoles.rolesOf(type).contains(role))
                    .count();
            assertThat(owners).as(role).isEqualTo(1);
            assertThat(DriveRoles.contentTypeOf(role)).isPresent();
        }
        assertThat(DriveRoles.rolesOf(ContentType.IMAGE))
                .containsExactly("logo", "product_photo", "reference_face", "persona_image", "image_asset");
        assertThat(DriveRoles.rolesOf(ContentType.VIDEO)).containsExactly("driver_video", "video_asset");
        assertThat(DriveRoles.rolesOf(ContentType.AUDIO)).containsExactly("audio_asset");
    }

    @Test
    void textRolesAreTheThreeTextRoles() {
        assertThat(DriveRoles.rolesOf(ContentType.TEXT))
                .containsExactly("brand_voice_text", "company_bio", "audience_note");
    }

    @Test
    void anUnknownRoleHasNoContentType() {
        assertThat(DriveRoles.contentTypeOf("selfie")).isEmpty();
        assertThat(DriveRoles.contentTypeOf("")).isEmpty();
        assertThat(DriveRoles.contentTypeOf(null)).isEmpty();
        assertThat(ContentType.fromWire("document")).isEmpty();
        assertThat(ContentType.fromWire("IMAGE")).isEmpty();
        assertThat(ContentType.fromWire("image")).contains(ContentType.IMAGE);
    }
}
