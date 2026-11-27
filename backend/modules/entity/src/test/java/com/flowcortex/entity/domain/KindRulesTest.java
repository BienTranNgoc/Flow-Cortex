package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;

/** The field keys and part roles each kind accepts, and the asset kind each role needs. */
class KindRulesTest {

    @Test
    void kindParsesItsLowerCaseWireValueOnly() {
        assertThat(EntityKind.parse("brand")).contains(EntityKind.BRAND);
        assertThat(EntityKind.parse("voice")).contains(EntityKind.VOICE);
        assertThat(EntityKind.PRODUCT.wire()).isEqualTo("product");
        assertThat(EntityKind.parse("Brand")).isEmpty();
        assertThat(EntityKind.parse("location")).isEmpty();
        assertThat(EntityKind.parse(null)).isEqualTo(Optional.empty());
    }

    @Test
    void eachKindAcceptsExactlyItsFieldKeys() {
        assertThat(KindRules.fieldKeys(EntityKind.BRAND))
                .containsExactlyInAnyOrder("voice", "companyBio", "audience", "colors", "do", "dont");
        assertThat(KindRules.fieldKeys(EntityKind.PRODUCT))
                .containsExactlyInAnyOrder("fullName", "description", "price", "highlights", "salesUrl");
        assertThat(KindRules.fieldKeys(EntityKind.CHARACTER))
                .containsExactlyInAnyOrder("description", "voiceLabel");
        assertThat(KindRules.fieldKeys(EntityKind.VOICE)).containsExactly("label");
        assertThat(KindRules.fieldKeys(EntityKind.CHARACTER)).doesNotContain("origin");
        assertThat(KindRules.fieldKeys(EntityKind.VOICE)).doesNotContain("userVoiceId");
    }

    @Test
    void onlyColorsDoAndDontAreLists() {
        assertThat(KindRules.isListField("colors")).isTrue();
        assertThat(KindRules.isListField("do")).isTrue();
        assertThat(KindRules.isListField("dont")).isTrue();
        assertThat(KindRules.isListField("highlights")).isFalse();
        assertThat(KindRules.isListField("voice")).isFalse();
    }

    @Test
    void eachKindAcceptsExactlyItsPartRoles() {
        assertThat(KindRules.partRoles(EntityKind.BRAND)).containsExactly("logo");
        assertThat(KindRules.partRoles(EntityKind.PRODUCT))
                .containsExactlyInAnyOrder("product_photo", "detail", "back", "worn");
        assertThat(KindRules.partRoles(EntityKind.CHARACTER)).containsExactlyInAnyOrder("portrait", "style");
        assertThat(KindRules.partRoles(EntityKind.VOICE)).containsExactly("voice_sample");
    }

    @Test
    void voiceSampleNeedsAudioAndEveryOtherRoleImage() {
        assertThat(KindRules.assetKindOf("voice_sample")).contains("audio");
        for (EntityKind kind : EntityKind.values()) {
            for (String role : KindRules.partRoles(kind)) {
                if (!role.equals("voice_sample")) {
                    assertThat(KindRules.assetKindOf(role)).as(role).contains("image");
                }
            }
        }
        assertThat(KindRules.assetKindOf("avatar")).isEmpty();
        assertThat(KindRules.assetKindOf(null)).isEmpty();
    }
}
