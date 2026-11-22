package com.flowcortex.content.section;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.LocalizedText;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class DocumentValidatorTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ContentDocumentDefinition RICH_ONLY =
            new ContentDocumentDefinition("terms", new LocalizedText("Điều khoản", "Terms"), List.of("richText"), false);
    private static final ContentDocumentDefinition BOTH = new ContentDocumentDefinition(
            "about", new LocalizedText("Giới thiệu", "About"), List.of("richText", "media"), false);

    private final DocumentValidator validator = new DocumentValidator(List.of(new RichTextSection(), new MediaSection()));

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static ObjectNode richText(String vi, String en) {
        ObjectNode data = JSON.createObjectNode();
        data.set("body", text(vi, en));
        return section("richText", 1, data);
    }

    private static ObjectNode media(Object mediaId, String altVi, String altEn, String captionVi, String captionEn) {
        ObjectNode data = JSON.createObjectNode();
        data.put("mediaId", String.valueOf(mediaId));
        data.set("alt", text(altVi, altEn));
        if (captionVi != null) {
            data.set("caption", text(captionVi, captionEn));
        }
        return section("media", 1, data);
    }

    private static ObjectNode section(String type, int schemaVersion, ObjectNode data) {
        ObjectNode section = JSON.createObjectNode();
        section.put("type", type);
        section.put("schemaVersion", schemaVersion);
        section.set("data", data);
        return section;
    }

    private static ObjectNode text(String vi, String en) {
        ObjectNode node = JSON.createObjectNode();
        node.put("vi", vi);
        node.put("en", en);
        return node;
    }

    private static ArrayNode sections(ObjectNode... items) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        return array;
    }

    private static ObjectNode seo(String titleVi, String titleEn, String descVi, String descEn) {
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text(titleVi, titleEn));
        seo.set("description", text(descVi, descEn));
        return seo;
    }

    private static ObjectNode okSeo() {
        return seo("Tiêu đề", "Title", "Mô tả", "Description");
    }

    private List<FieldError> validate(ContentDocumentDefinition def, JsonNode sections, JsonNode seo) {
        return validator.validate(def, sections, seo, Set.of());
    }

    private static List<String> paths(List<FieldError> errors) {
        return errors.stream().map(FieldError::path).toList();
    }

    @Test
    void acceptsExactly100Sections() {
        ArrayNode items = JSON.createArrayNode();
        for (int i = 0; i < 100; i++) {
            items.add(richText("a", "b"));
        }

        assertThat(validate(RICH_ONLY, items, okSeo())).isEmpty();
    }

    @Test
    void refuses101Sections() {
        ArrayNode items = JSON.createArrayNode();
        for (int i = 0; i < 101; i++) {
            items.add(richText("a", "b"));
        }

        List<FieldError> errors = validate(RICH_ONLY, items, okSeo());

        assertThat(errors).containsExactly(new FieldError("sections", FieldError.TOO_MANY));
    }

    @Test
    void refusesATypeTheDocumentDoesNotAllow() {
        List<FieldError> errors = validate(RICH_ONLY,
                sections(richText("a", "b"), media(UUID.randomUUID(), "a", "b", null, null)), okSeo());

        assertThat(errors).containsExactly(new FieldError("sections[1].type", FieldError.TYPE_NOT_ALLOWED));
    }

    @Test
    void refusesAnUnknownSectionType() {
        List<FieldError> errors = validate(RICH_ONLY, sections(section("carousel", 1, JSON.createObjectNode())), okSeo());

        assertThat(errors).containsExactly(new FieldError("sections[0].type", FieldError.TYPE_NOT_ALLOWED));
    }

    @Test
    void refusesAnUnknownSchemaVersion() {
        ObjectNode data = JSON.createObjectNode();
        data.set("body", text("a", "b"));

        List<FieldError> errors = validate(RICH_ONLY, sections(section("richText", 2, data)), okSeo());

        assertThat(errors).containsExactly(new FieldError("sections[0].schemaVersion", FieldError.UNSUPPORTED_VERSION));
    }

    @Test
    void refusesASectionThatIsNotAnObjectWithATypeAndData() {
        assertThat(paths(validate(RICH_ONLY, json("[\"x\"]"), okSeo()))).containsExactly("sections[0]");
        assertThat(paths(validate(RICH_ONLY, json("[{\"type\":\"richText\",\"schemaVersion\":1}]"), okSeo())))
                .containsExactly("sections[0].data");
        assertThat(paths(validate(RICH_ONLY, json("[{\"type\":\"richText\",\"data\":{}}]"), okSeo())))
                .containsExactly("sections[0].schemaVersion");
        assertThat(paths(validate(RICH_ONLY, json("{}"), okSeo()))).containsExactly("sections");
        assertThat(paths(validate(RICH_ONLY, null, okSeo()))).containsExactly("sections");
    }

    @Test
    void acceptsA50000CharacterBody() {
        String body = "a".repeat(50_000);

        assertThat(validate(RICH_ONLY, sections(richText(body, body)), okSeo())).isEmpty();
    }

    @Test
    void refusesA50001CharacterBodyNamingItsPath() {
        String body = "a".repeat(50_001);
        ArrayNode items = sections(richText("a", "b"), richText("a", "b"), richText("a", "b"), richText(body, "b"));

        List<FieldError> errors = validate(RICH_ONLY, items, okSeo());

        assertThat(errors).containsExactly(new FieldError("sections[3].data.body.vi", FieldError.TOO_LONG));
    }

    @Test
    void countsCharactersNotUtf16Units() {
        String emoji = "😀".repeat(50_000);

        assertThat(validate(RICH_ONLY, sections(richText(emoji, "b")), okSeo())).isEmpty();
    }

    @Test
    void seoTitleBoundIs200AndDescription500() {
        assertThat(validate(RICH_ONLY, sections(), seo("t".repeat(200), "t".repeat(200), "d".repeat(500), "d".repeat(500))))
                .isEmpty();

        assertThat(validate(RICH_ONLY, sections(), seo("t".repeat(201), "t", "d", "d")))
                .containsExactly(new FieldError("seoMeta.title.vi", FieldError.TOO_LONG));
        assertThat(validate(RICH_ONLY, sections(), seo("t", "t".repeat(201), "d", "d")))
                .containsExactly(new FieldError("seoMeta.title.en", FieldError.TOO_LONG));
        assertThat(validate(RICH_ONLY, sections(), seo("t", "t", "d".repeat(501), "d")))
                .containsExactly(new FieldError("seoMeta.description.vi", FieldError.TOO_LONG));
        assertThat(validate(RICH_ONLY, sections(), seo("t", "t", "d", "d".repeat(501))))
                .containsExactly(new FieldError("seoMeta.description.en", FieldError.TOO_LONG));
    }

    private static int bytes(ArrayNode items, ObjectNode seo) {
        return items.toString().getBytes(StandardCharsets.UTF_8).length
                + seo.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    /** Six 50,000-character Vietnamese bodies (3 bytes each), the English body of the first padded to the target. */
    private static ArrayNode sectionsTotalling(int target, ObjectNode seo) {
        ArrayNode items = JSON.createArrayNode();
        for (int i = 0; i < 6; i++) {
            items.add(richText("ế".repeat(50_000), ""));
        }
        int pad = target - bytes(items, seo);
        ((ObjectNode) items.get(0).get("data").get("body")).put("en", "x".repeat(pad));
        assertThat(bytes(items, seo)).isEqualTo(target);
        return items;
    }

    @Test
    void acceptsSectionsAndSeoTotalling921600Bytes() {
        ObjectNode seo = okSeo();

        assertThat(validate(RICH_ONLY, sectionsTotalling(921_600, seo), seo)).isEmpty();
    }

    @Test
    void refusesSectionsAndSeoTotalling921601BytesNamingSections() {
        ObjectNode seo = okSeo();

        assertThat(validate(RICH_ONLY, sectionsTotalling(921_601, seo), seo))
                .containsExactly(new FieldError("sections", FieldError.TOO_LARGE));
    }

    @Test
    void aBlankTextIsValidInADraft() {
        assertThat(validate(RICH_ONLY, sections(richText("", "")), seo("", "", "", ""))).isEmpty();
    }

    @Test
    void aMissingLocaleKeyIsInvalid() {
        ObjectNode data = JSON.createObjectNode();
        ObjectNode body = JSON.createObjectNode();
        body.put("vi", "a");
        data.set("body", body);
        ObjectNode seo = okSeo();
        ((ObjectNode) seo.get("title")).remove("en");

        List<FieldError> errors = validate(RICH_ONLY, sections(section("richText", 1, data)), seo);

        assertThat(errors).containsExactlyInAnyOrder(
                new FieldError("sections[0].data.body.en", FieldError.REQUIRED),
                new FieldError("seoMeta.title.en", FieldError.REQUIRED));
    }

    @Test
    void aNonTextLocaleValueIsMalformed() {
        ObjectNode data = JSON.createObjectNode();
        data.set("body", json("{\"vi\":5,\"en\":\"b\"}"));

        assertThat(validate(RICH_ONLY, sections(section("richText", 1, data)), okSeo()))
                .containsExactly(new FieldError("sections[0].data.body.vi", FieldError.MALFORMED));
    }

    @Test
    void refusesAKeyOutsideTheSectionEnvelopeNamingIt() {
        ObjectNode item = richText("a", "b");
        item.put("id", "x");

        assertThat(validate(RICH_ONLY, sections(item), okSeo()))
                .containsExactly(new FieldError("sections[0].id", FieldError.MALFORMED));
    }

    @Test
    void refusesAKeyOutsideTheSectionDataNamingIt() {
        ObjectNode item = richText("a", "b");
        ((ObjectNode) item.get("data")).put("html", "<script></script>");
        ObjectNode mediaItem = media(UUID.randomUUID(), "a", "b", null, null);
        ((ObjectNode) mediaItem.get("data")).set("body", text("a", "b"));

        assertThat(validator.validate(BOTH, sections(item, mediaItem), okSeo(), Set.of())).containsExactlyInAnyOrder(
                new FieldError("sections[0].data.html", FieldError.MALFORMED),
                new FieldError("sections[1].data.body", FieldError.MALFORMED),
                new FieldError("sections[1].data.mediaId", FieldError.MEDIA_NOT_FOUND));
    }

    @Test
    void refusesALanguageOtherThanViAndEnInAText() {
        ObjectNode item = richText("a", "b");
        ((ObjectNode) item.get("data").get("body")).put("fr", "c");
        ObjectNode seo = okSeo();
        ((ObjectNode) seo.get("title")).put("fr", "c");

        assertThat(validate(RICH_ONLY, sections(item), seo)).containsExactlyInAnyOrder(
                new FieldError("sections[0].data.body.fr", FieldError.MALFORMED),
                new FieldError("seoMeta.title.fr", FieldError.MALFORMED));
    }

    @Test
    void refusesAKeyOutsideSeoMetaNamingIt() {
        ObjectNode seo = okSeo();
        seo.put("robots", "noindex");

        assertThat(validate(RICH_ONLY, sections(), seo))
                .containsExactly(new FieldError("seoMeta.robots", FieldError.MALFORMED));
    }

    @Test
    void aMissingSeoMetaIsRefusedNamingEachText() {
        assertThat(paths(validate(RICH_ONLY, sections(), null)))
                .containsExactly("seoMeta.title", "seoMeta.description");
    }

    @Test
    void aMediaSectionWithAnUnknownIdIsRefusedNamingItsPath() {
        UUID known = UUID.randomUUID();
        UUID unknown = UUID.randomUUID();
        ArrayNode items = sections(richText("a", "b"), media(known, "a", "b", null, null),
                media(unknown, "a", "b", null, null));

        List<FieldError> errors = validator.validate(BOTH, items, okSeo(), Set.of(known));

        assertThat(errors).containsExactly(new FieldError("sections[2].data.mediaId", FieldError.MEDIA_NOT_FOUND));
    }

    @Test
    void aMediaSectionWithAMalformedIdIsRefused() {
        ArrayNode items = sections(media("not-a-uuid", "a", "b", null, null));

        List<FieldError> errors = validator.validate(BOTH, items, okSeo(), Set.of());

        assertThat(errors).containsExactly(new FieldError("sections[0].data.mediaId", FieldError.MALFORMED));
    }

    @Test
    void aMissingMediaIdIsRequired() {
        ObjectNode data = JSON.createObjectNode();
        data.set("alt", text("a", "b"));

        assertThat(validator.validate(BOTH, sections(section("media", 1, data)), okSeo(), Set.of()))
                .containsExactly(new FieldError("sections[0].data.mediaId", FieldError.REQUIRED));
    }

    @Test
    void aMediaCaptionIsOptional() {
        UUID id = UUID.randomUUID();

        assertThat(validator.validate(BOTH, sections(media(id, "a", "b", null, null)), okSeo(), Set.of(id))).isEmpty();
        assertThat(validator.validate(BOTH, sections(media(id, "a", "b", "", "")), okSeo(), Set.of(id))).isEmpty();
        assertThat(validator.validate(BOTH, sections(media(id, "a", "b", "c".repeat(50_001), "x")), okSeo(), Set.of(id)))
                .containsExactly(new FieldError("sections[0].data.caption.vi", FieldError.TOO_LONG));
    }

    @Test
    void referencedMediaCollectsEveryWellFormedLibraryId() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        ObjectNode malformed = JSON.createObjectNode();
        malformed.put("mediaId", "not-a-uuid");
        malformed.set("alt", text("a", "b"));

        Set<UUID> ids = validator.referencedMedia(BOTH, sections(media(first, "a", "b", null, null),
                richText("a", "b"), media(second, "a", "b", null, null), media(first, "a", "b", null, null),
                section("media", 1, malformed)));

        assertThat(ids).containsExactlyInAnyOrder(first, second);
    }

    @Test
    void referencedMediaToleratesWhatTheValidatorWillRefuse() {
        ArrayNode tooMany = JSON.createArrayNode();
        for (int i = 0; i <= DocumentValidator.MAX_SECTIONS; i++) {
            tooMany.add(media(UUID.randomUUID(), "a", "b", null, null));
        }

        assertThat(validator.referencedMedia(BOTH, null)).isEmpty();
        assertThat(validator.referencedMedia(BOTH, JSON.createObjectNode())).isEmpty();
        assertThat(validator.referencedMedia(BOTH, tooMany)).isEmpty();
        assertThat(validator.referencedMedia(RICH_ONLY, sections(media(UUID.randomUUID(), "a", "b", null, null))))
                .isEmpty();
    }
}
