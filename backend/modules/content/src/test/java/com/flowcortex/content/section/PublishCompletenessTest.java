package com.flowcortex.content.section;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class PublishCompletenessTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final ContentDocumentDefinition DEF = new ContentDocumentDefinition(
            "about", new LocalizedText("Giới thiệu", "About"), List.of("richText", "media"), false);

    private final PublishCompleteness completeness = new PublishCompleteness(List.of(new RichTextSection(), new MediaSection()));

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    private static String rich(String vi, String en) {
        return "{\"type\":\"richText\",\"schemaVersion\":1,\"data\":{\"body\":{\"vi\":\"" + vi + "\",\"en\":\"" + en + "\"}}}";
    }

    private static String media(String altVi, String altEn, String caption) {
        return "{\"type\":\"media\",\"schemaVersion\":1,\"data\":{\"mediaId\":\"" + UUID.randomUUID()
                + "\",\"alt\":{\"vi\":\"" + altVi + "\",\"en\":\"" + altEn + "\"},\"caption\":{\"vi\":\"" + caption
                + "\",\"en\":\"" + caption + "\"}}}";
    }

    private static JsonNode seo(String titleVi, String titleEn, String desc) {
        return json("{\"title\":{\"vi\":\"" + titleVi + "\",\"en\":\"" + titleEn + "\"},\"description\":{\"vi\":\"" + desc
                + "\",\"en\":\"" + desc + "\"}}");
    }

    @Test
    void listsABlankEnglishBody() {
        assertThat(completeness.missing(DEF, json("[" + rich("xin chào", " ") + "]"), seo("t", "t", "d")))
                .containsExactly("sections[0].data.body.en");
    }

    @Test
    void listsABlankVietnameseAltText() {
        assertThat(completeness.missing(DEF, json("[" + media("", "alt", "c") + "]"), seo("t", "t", "d")))
                .containsExactly("sections[0].data.alt.vi");
    }

    @Test
    void listsABlankSeoTitleInEitherLanguage() {
        assertThat(completeness.missing(DEF, json("[]"), seo("", "t", "d"))).containsExactly("seoMeta.title.vi");
        assertThat(completeness.missing(DEF, json("[]"), seo("t", "", "d"))).containsExactly("seoMeta.title.en");
        assertThat(completeness.missing(DEF, json("[]"), seo("", "", "d")))
                .containsExactly("seoMeta.title.vi", "seoMeta.title.en");
    }

    @Test
    void aBlankCaptionOrSeoDescriptionIsNotMissing() {
        assertThat(completeness.missing(DEF, json("[" + media("a", "b", "") + "]"), seo("t", "t", ""))).isEmpty();
    }

    @Test
    void listsEveryMissingFieldInDocumentOrder() {
        JsonNode sections = json("[" + rich("a", "") + "," + media("", "b", "") + "]");

        assertThat(completeness.missing(DEF, sections, seo("t", "", "d")))
                .containsExactly("sections[0].data.body.en", "sections[1].data.alt.vi", "seoMeta.title.en");
    }

    @Test
    void aCompleteDocumentHasNothingMissing() {
        assertThat(completeness.missing(DEF, json("[" + rich("a", "b") + "," + media("a", "b", "c") + "]"), seo("t", "t", "d")))
                .isEmpty();
    }
}
