package com.flowcortex.entity.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.entity.domain.EntityRules.Checked;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** One case per rule: each check answers the cleaned value or the violation, never an exception. */
class EntityRulesTest {

    private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID OTHER_ASSET = UUID.fromString("00000000-0000-0000-0000-000000000002");

    private static void assertViolation(Checked<?> checked, String field, String messageKey) {
        assertThat(checked.valid()).isFalse();
        assertThat(checked.violation()).isEqualTo(new RuleViolation(field, messageKey));
    }

    private static PartInput part(UUID asset, String role) {
        return new PartInput(asset, role, null);
    }

    private static List<String> tags(int count) {
        return IntStream.range(0, count).mapToObj(i -> "tag " + i).toList();
    }

    @Test
    void blankNameIsRequired() {
        assertViolation(EntityRules.checkName("   "), "name", "entity.name.invalid");
        assertViolation(EntityRules.checkName(null), "name", "entity.name.invalid");
    }

    @Test
    void name201CodePointsIsTooLong() {
        assertThat(EntityRules.checkName("x".repeat(200)).valid()).isTrue();
        assertViolation(EntityRules.checkName("x".repeat(201)), "name", "entity.name.invalid");
        String emoji = "😀".repeat(200);
        assertThat(EntityRules.checkName(emoji).value()).isEqualTo(emoji);
        assertViolation(EntityRules.checkName(emoji + "😀"), "name", "entity.name.invalid");
    }

    @Test
    void nameIsNfcTrimmed() {
        String decomposed = Normalizer.normalize("  Áo thun  trắng ", Normalizer.Form.NFD);

        assertThat(EntityRules.checkName(decomposed).value()).isEqualTo("Áo thun  trắng");
    }

    @Test
    void unknownKeyIsRefused() {
        assertViolation(EntityRules.checkFields(EntityKind.CHARACTER, Map.of("price", "199000")),
                "fields", "entity.fields.unknownKey");
        assertViolation(EntityRules.checkFields(EntityKind.CHARACTER, Map.of("origin", "ai")),
                "fields", "entity.fields.unknownKey");
        assertViolation(EntityRules.checkFields(EntityKind.VOICE, Map.of("userVoiceId", "v1")),
                "fields", "entity.fields.unknownKey");
    }

    @Test
    void nonHexColourIsInvalid() {
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("colors", List.of("#ff0000", "red"))),
                "fields", "entity.fields.invalid");
        Checked<Map<String, Object>> valid = EntityRules.checkFields(EntityKind.BRAND,
                Map.of("colors", List.of("#fff", "#FF0000", "#ff000080")));
        assertThat(valid.valid()).isTrue();
        assertThat(valid.value()).containsEntry("colors", List.of("#fff", "#FF0000", "#ff000080"));
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("colors", List.of("#ffff"))),
                "fields", "entity.fields.invalid");
    }

    @Test
    void doMustBeAListOfStrings() {
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", "be kind")),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("dont", List.of("shout", 7))),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("price", 199000)),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("highlights", List.of("a"))),
                "fields", "entity.fields.invalid");
        Map<String, Object> withNull = new LinkedHashMap<>();
        withNull.put("price", null);
        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, withNull), "fields", "entity.fields.invalid");
        assertThat(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", List.of("be kind"))).valid()).isTrue();
    }

    @Test
    void string4000IsAcceptedAnd4001Invalid() {
        assertThat(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("description", "d".repeat(4000))).valid())
                .isTrue();
        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("description", "d".repeat(4001))),
                "fields", "entity.fields.invalid");
        assertThat(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("description", "😀".repeat(4000))).valid())
                .isTrue();
    }

    @Test
    void list21EntriesIsInvalid() {
        assertThat(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", repeated(20, 1))).valid()).isTrue();
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", repeated(21, 1))),
                "fields", "entity.fields.invalid");
    }

    @Test
    void serializedOver32768IsInvalid() {
        List<String> exactly = new ArrayList<>(repeated(8, 4000));
        exactly.add("e".repeat(733));
        assertThat(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", exactly)).valid()).isTrue();

        List<String> oneOver = new ArrayList<>(repeated(8, 4000));
        oneOver.add("e".repeat(734));
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", oneOver)),
                "fields", "entity.fields.invalid");
    }

    @Test
    void serializedSizeCountsJsonEscapes() {
        Map<String, Object> plain = Map.of("do", repeated(8, 4000), "voice", "e".repeat(500));
        Map<String, Object> quoted = Map.of("do", repeated(8, 4000), "voice", "\"".repeat(500));

        assertThat(EntityRules.checkFields(EntityKind.BRAND, plain).valid()).isTrue();
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, quoted), "fields", "entity.fields.invalid");
    }

    @Test
    void absentFieldsAreEmpty() {
        assertThat(EntityRules.checkFields(EntityKind.VOICE, null).value()).isEmpty();
        assertThat(EntityRules.checkFields(EntityKind.VOICE, Map.of()).value()).isEmpty();
    }

    @Test
    void twentyOnePartsIsTooMany() {
        List<PartInput> twenty = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            twenty.add(part(ASSET, "product_photo"));
        }
        assertThat(EntityRules.checkPartShapes(EntityKind.PRODUCT, twenty).valid()).isTrue();
        twenty.add(part(ASSET, "not-a-role"));
        assertViolation(EntityRules.checkPartShapes(EntityKind.PRODUCT, twenty), "parts", "entity.parts.tooMany");
    }

    @Test
    void roleOfAnotherKindIsNotAllowed() {
        assertViolation(EntityRules.checkPartShapes(EntityKind.BRAND, List.of(part(ASSET, "portrait"))),
                "parts", "entity.parts.roleNotAllowed");
        assertViolation(EntityRules.checkPartShapes(EntityKind.BRAND, List.of(part(ASSET, null))),
                "parts", "entity.parts.roleNotAllowed");
        assertViolation(EntityRules.checkPartShapes(EntityKind.BRAND, List.of(part(null, "logo"))),
                "parts", "entity.parts.invalid");
        assertThat(EntityRules.checkPartShapes(EntityKind.BRAND, null).value()).isEmpty();
    }

    @Test
    void assetMissingFromTheSpaceIsNotFound() {
        List<PartInput> parts = List.of(part(ASSET, "logo"), part(OTHER_ASSET, "logo"));

        assertThat(EntityRules.checkPartAssets(parts, Map.of(ASSET, "image", OTHER_ASSET, "image"))).isEmpty();
        assertThat(EntityRules.checkPartAssets(parts, Map.of(ASSET, "image")))
                .contains(new RuleViolation("parts", "entity.parts.assetNotFound"));
    }

    @Test
    void imageForVoiceSampleIsAKindMismatch() {
        List<PartInput> parts = List.of(part(ASSET, "voice_sample"));

        assertThat(EntityRules.checkPartAssets(parts, Map.of(ASSET, "audio"))).isEmpty();
        assertThat(EntityRules.checkPartAssets(parts, Map.of(ASSET, "image")))
                .contains(new RuleViolation("parts", "entity.parts.kindMismatch"));
        assertThat(EntityRules.checkPartAssets(List.of(part(ASSET, "logo")), Map.of(ASSET, "audio")))
                .contains(new RuleViolation("parts", "entity.parts.kindMismatch"));
    }

    @Test
    void notFoundIsReportedBeforeKindMismatch() {
        List<PartInput> parts = List.of(part(ASSET, "voice_sample"), part(OTHER_ASSET, "voice_sample"));

        assertThat(EntityRules.checkPartAssets(parts, Map.of(ASSET, "image")))
                .contains(new RuleViolation("parts", "entity.parts.assetNotFound"));
    }

    @Test
    void partLabelsAreNormalizedAndCapped() {
        Checked<List<PartInput>> checked = EntityRules.checkPartShapes(EntityKind.PRODUCT,
                List.of(new PartInput(ASSET, "product_photo", "  Mặt   trước "), part(ASSET, "back")));

        assertThat(checked.value()).containsExactly(new PartInput(ASSET, "product_photo", "Mặt trước"),
                part(ASSET, "back"));
        assertViolation(EntityRules.checkPartShapes(EntityKind.PRODUCT,
                List.of(new PartInput(ASSET, "back", "l".repeat(61)))), "parts", "entity.parts.labelInvalid");
        assertViolation(EntityRules.checkPartShapes(EntityKind.PRODUCT,
                List.of(new PartInput(ASSET, "back", "   "))), "parts", "entity.parts.labelInvalid");
    }

    @Test
    void twentyOneDistinctTagsIsTooMany() {
        assertThat(EntityRules.checkTags(tags(20)).valid()).isTrue();
        assertViolation(EntityRules.checkTags(tags(21)), "tags", "entity.tags.tooMany");
    }

    @Test
    void sameKeyCountsOnce() {
        List<String> twentyOneWithRepeat = new ArrayList<>(tags(20));
        twentyOneWithRepeat.add("TAG   0");
        twentyOneWithRepeat.add("Tag 0");

        Checked<List<String>> checked = EntityRules.checkTags(twentyOneWithRepeat);

        assertThat(checked.valid()).isTrue();
        assertThat(checked.value()).hasSize(20).startsWith("tag 0").doesNotContain("TAG 0");
        assertThat(EntityRules.checkTags(List.of("Mùa Hè", " mùa  hè ", "Mua he")).value()).containsExactly("Mùa Hè");
        assertThat(EntityRules.checkTags(null).value()).isEmpty();
    }

    @Test
    void tag41IsInvalid() {
        assertThat(EntityRules.checkTags(List.of("t".repeat(40))).valid()).isTrue();
        assertViolation(EntityRules.checkTags(List.of("t".repeat(41))), "tags", "entity.tags.invalid");
        assertViolation(EntityRules.checkTags(List.of("ok", "  ")), "tags", "entity.tags.invalid");
        assertViolation(EntityRules.checkTags(Arrays.asList("ok", null)), "tags", "entity.tags.invalid");
        assertViolation(EntityRules.checkTags(List.of("́")), "tags", "entity.tags.invalid");
    }

    @Test
    void category41IsInvalid() {
        assertThat(EntityRules.checkCategory("c".repeat(40)).valid()).isTrue();
        assertViolation(EntityRules.checkCategory("c".repeat(41)), "categoryLabel", "entity.categoryLabel.invalid");
        assertThat(EntityRules.checkCategory("  Đồ   uống ").value()).isEqualTo("Đồ uống");
        assertThat(EntityRules.checkCategory(null).value()).isNull();
        assertThat(EntityRules.checkCategory(null).valid()).isTrue();
    }

    @Test
    void blankCategoryIsInvalid() {
        assertViolation(EntityRules.checkCategory("   "), "categoryLabel", "entity.categoryLabel.invalid");
        assertViolation(EntityRules.checkCategory(""), "categoryLabel", "entity.categoryLabel.invalid");
    }

    @Test
    void label61IsInvalid() {
        assertThat(EntityRules.checkLabel("l".repeat(60)).valid()).isTrue();
        assertViolation(EntityRules.checkLabel("l".repeat(61)), "parts", "entity.parts.labelInvalid");
        assertThat(EntityRules.checkLabel(null).value()).isNull();
    }

    @Test
    void amzSignatureInAFieldIsRefusedAsFields() {
        Map<String, Object> fields = Map.of("salesUrl", "https://s3.example/x?X-Amz-Signature=abc");

        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, fields), "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", List.of("fine", "x-AMZ-credential"))),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkName("a X-AMZ-b"), "name", "entity.name.invalid");
        assertViolation(EntityRules.checkLabel("x-amz-date"), "parts", "entity.parts.labelInvalid");
        assertViolation(EntityRules.checkCategory("x-amz-date"), "categoryLabel", "entity.categoryLabel.invalid");
    }

    @Test
    void nulCharacterIsRefusedEverywhereTextIsStored() {
        assertViolation(EntityRules.checkName("No\u0000di"), "name", "entity.name.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("description", "a\u0000b")),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkFields(EntityKind.BRAND, Map.of("do", List.of("fine", "a\u0000"))),
                "fields", "entity.fields.invalid");
        assertViolation(EntityRules.checkTags(List.of("a\u0000b")), "tags", "entity.tags.invalid");
        assertViolation(EntityRules.checkCategory("a\u0000b"), "categoryLabel", "entity.categoryLabel.invalid");
        assertViolation(EntityRules.checkLabel("a\u0000b"), "parts", "entity.parts.labelInvalid");
    }

    @Test
    void dataUriInATagIsRefusedAsTags() {
        assertViolation(EntityRules.checkTags(List.of("data:image/png;base64,AAAA")), "tags", "entity.tags.invalid");
        assertViolation(EntityRules.checkTags(List.of("  DATA:text/plain,hi")), "tags", "entity.tags.invalid");
    }

    @Test
    void embeddedBase64DataUriIsRefused() {
        assertThat(EntityRules.containsSignedUrlOrDataUri("see data:image/png;base64,AAAA")).isTrue();
        assertThat(EntityRules.containsSignedUrlOrDataUri("see DATA:image/svg+xml;charset=utf-8;base64,AAAA"))
                .isTrue();
        assertThat(EntityRules.containsSignedUrlOrDataUri(List.of("ok", Map.of("k", "data:a/b;base64,x")))).isTrue();
        assertThat(EntityRules.containsSignedUrlOrDataUri(" data:,x")).isTrue();
    }

    @Test
    void metadataColonInTextIsAccepted() {
        assertThat(EntityRules.containsSignedUrlOrDataUri("Metadata: cotton")).isFalse();
        assertThat(EntityRules.containsSignedUrlOrDataUri("see data: later")).isFalse();
        assertThat(EntityRules.containsSignedUrlOrDataUri(null)).isFalse();
        assertThat(EntityRules.containsSignedUrlOrDataUri(42)).isFalse();
        assertThat(EntityRules.checkFields(EntityKind.PRODUCT, Map.of("description", "Metadata: cotton 100%")).valid())
                .isTrue();
    }

    @Test
    void violationTextNeverHoldsTheValue() {
        String secret = "https://s3.example/x?X-Amz-Signature=SECRET123";
        List<Checked<?>> refusals = List.of(
                EntityRules.checkName(secret),
                EntityRules.checkFields(EntityKind.PRODUCT, Map.of("salesUrl", secret)),
                EntityRules.checkFields(EntityKind.CHARACTER, Map.of(secret, "x")),
                EntityRules.checkTags(List.of(secret)),
                EntityRules.checkCategory(secret),
                EntityRules.checkLabel(secret),
                EntityRules.checkPartShapes(EntityKind.PRODUCT, List.of(new PartInput(ASSET, "back", secret))));

        for (Checked<?> refusal : refusals) {
            assertThat(refusal.valid()).isFalse();
            assertThat(refusal.violation().toString()).doesNotContain("SECRET123").doesNotContain("Signature");
        }
    }

    private static List<String> repeated(int count, int length) {
        return Collections.nCopies(count, "e".repeat(length));
    }
}
