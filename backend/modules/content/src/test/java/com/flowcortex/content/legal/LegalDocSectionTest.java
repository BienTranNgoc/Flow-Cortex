package com.flowcortex.content.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.section.DocumentValidator;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

class LegalDocSectionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ContentDocumentDefinition PRIVACY = new ContentDocumentDefinition(
            "legal-privacy", new LocalizedText("Chính sách bảo mật", "Privacy"), List.of("legalDoc"), true, 1, 1);

    private final LegalDocSection section = new LegalDocSection();

    private static ObjectNode text(String vi, String en) {
        ObjectNode node = JSON.createObjectNode();
        node.put("vi", vi);
        node.put("en", en);
        return node;
    }

    private static ObjectNode data(String title, String body, String docVersion, String effectiveDate) {
        ObjectNode data = JSON.createObjectNode();
        data.set("title", text(title, title));
        data.set("body", text(body, body));
        data.put("docVersion", docVersion);
        data.put("effectiveDate", effectiveDate);
        return data;
    }

    private static ObjectNode valid() {
        return data("Chính sách", "Nội dung", "1.0", "2026-12-01");
    }

    private static List<FieldError> validate(ObjectNode data) {
        return new LegalDocSection().validate(data);
    }

    @Test
    void acceptsTheBoundsInclusive() {
        ObjectNode data = data("t".repeat(200), "b".repeat(50_000), "aZ09._-".repeat(4) + "abcd", "2028-02-29");

        assertThat(data.get("docVersion").stringValue()).hasSize(32);
        assertThat(validate(data)).isEmpty();
    }

    @Test
    void refusesAnOverLongTitleNamingItsPath() {
        ObjectNode data = valid();
        data.set("title", text("ok", "t".repeat(201)));

        assertThat(validate(data)).containsExactly(new FieldError("title.en", FieldError.TOO_LONG));
    }

    @Test
    void refusesAnOverLongBody() {
        ObjectNode data = valid();
        data.set("body", text("b".repeat(50_001), "ok"));

        assertThat(validate(data)).containsExactly(new FieldError("body.vi", FieldError.TOO_LONG));
    }

    @Test
    void refusesADocVersionOutsideItsCharacters() {
        ObjectNode data = valid();
        data.put("docVersion", "v 1");

        assertThat(validate(data)).containsExactly(new FieldError("docVersion", FieldError.FORMAT));
    }

    @Test
    void refusesADocVersionOver32() {
        ObjectNode data = valid();
        data.put("docVersion", "1".repeat(33));

        assertThat(validate(data)).containsExactly(new FieldError("docVersion", FieldError.TOO_LONG));
    }

    @Test
    void refusesAnImpossibleDate() {
        ObjectNode data = valid();
        data.put("effectiveDate", "2026-02-30");

        assertThat(validate(data)).containsExactly(new FieldError("effectiveDate", FieldError.DATE));
    }

    @Test
    void refusesANonIsoDate() {
        for (String date : List.of("01/12/2026", "2026-1-1", "+12026-01-01", "2026-12-01T00:00:00Z", " 2026-12-01")) {
            ObjectNode data = valid();
            data.put("effectiveDate", date);

            assertThat(validate(data)).as(date).containsExactly(new FieldError("effectiveDate", FieldError.DATE));
        }
    }

    @Test
    void refusesAnUnknownKey() {
        ObjectNode data = valid();
        data.put("authoritativeNotice", "x");
        ObjectNode legalSection = JSON.createObjectNode();
        legalSection.put("type", LegalDocSection.TYPE);
        legalSection.put("schemaVersion", 1);
        legalSection.set("data", data);

        List<FieldError> errors = new DocumentValidator(List.of(section)).validate(
                PRIVACY, JSON.createArrayNode().add(legalSection), seo(), Set.of());

        assertThat(errors).containsExactly(
                new FieldError("sections[0].data.authoritativeNotice", FieldError.MALFORMED));
    }

    @Test
    void refusesAWrongType() {
        ObjectNode data = valid();
        data.put("docVersion", 1);
        data.put("effectiveDate", true);
        data.put("title", "plain");

        assertThat(validate(data)).containsExactlyInAnyOrder(
                new FieldError("docVersion", FieldError.MALFORMED),
                new FieldError("effectiveDate", FieldError.MALFORMED),
                new FieldError("title", FieldError.MALFORMED));
    }

    @Test
    void refusesAMissingField() {
        ObjectNode data = valid();
        data.remove("docVersion");
        data.remove("effectiveDate");

        assertThat(validate(data)).containsExactly(
                new FieldError("docVersion", FieldError.REQUIRED),
                new FieldError("effectiveDate", FieldError.REQUIRED));
    }

    @Test
    void refusesAVersionAndDateOfOnlySpaces() {
        ObjectNode data = valid();
        data.put("docVersion", "   ");
        data.put("effectiveDate", "   ");

        assertThat(validate(data)).containsExactlyInAnyOrder(
                new FieldError("docVersion", FieldError.FORMAT),
                new FieldError("effectiveDate", FieldError.DATE));
    }

    @Test
    void blankValuesAreValidInADraft() {
        assertThat(validate(data("", "", "", ""))).isEmpty();
    }

    @Test
    void listsEveryBlankRequiredFieldInOrder() {
        assertThat(section.blankRequiredTexts(data("", " ", "", ""))).containsExactly(
                "title.vi", "title.en", "body.vi", "body.en", "docVersion", "effectiveDate");
        assertThat(section.blankRequiredTexts(valid())).isEmpty();
    }

    private static JsonNode seo() {
        ObjectNode seo = JSON.createObjectNode();
        seo.set("title", text("Tiêu đề", "Title"));
        seo.set("description", text("Mô tả", "Description"));
        return seo;
    }
}
