package com.flowcortex.content.section;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.api.SectionType;
import java.util.List;
import org.junit.jupiter.api.Test;

class DocumentRegistryTest {

    private static final List<SectionType> TYPES = List.of(new RichTextSection(), new MediaSection());

    private static ContentDocumentDefinition def(String slug, String... types) {
        return new ContentDocumentDefinition(slug, new LocalizedText("Tiêu đề", "Title"), List.of(types), false);
    }

    @Test
    void refusesASlugWithUppercaseOrSpaces() {
        assertThatThrownBy(() -> new DocumentRegistry(List.of(def("Bad Slug", "richText")), TYPES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Bad Slug");
        assertThatThrownBy(() -> new DocumentRegistry(List.of(def("Terms", "richText")), TYPES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Terms");
    }

    @Test
    void refusesASlugOver64Characters() {
        String ok = "a".repeat(64);
        String tooLong = "a".repeat(65);
        assertThat(new DocumentRegistry(List.of(def(ok, "richText")), TYPES).find(ok)).isPresent();
        assertThatThrownBy(() -> new DocumentRegistry(List.of(def(tooLong, "richText")), TYPES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(tooLong);
    }

    @Test
    void refusesTwoDefinitionsWithOneSlug() {
        assertThatThrownBy(() -> new DocumentRegistry(
                        List.of(def("terms", "richText"), def("terms", "media")), TYPES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terms");
    }

    @Test
    void refusesASectionTypeNoBeanRegisters() {
        assertThatThrownBy(() -> new DocumentRegistry(List.of(def("terms", "richText", "carousel")), TYPES))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("carousel");
    }

    @Test
    void findsARegisteredSlugAndNothingElse() {
        DocumentRegistry registry = new DocumentRegistry(List.of(def("terms", "richText")), TYPES);

        assertThat(registry.find("terms")).isPresent();
        assertThat(registry.find("no-such-doc")).isEmpty();
        assertThat(registry.find(null)).isEmpty();
    }

    @Test
    void keepsRegistrationOrder() {
        DocumentRegistry registry = new DocumentRegistry(
                List.of(def("zeta", "richText"), def("alpha", "media"), def("mid", "richText")), TYPES);

        assertThat(registry.all()).extracting(ContentDocumentDefinition::slug).containsExactly("zeta", "alpha", "mid");
    }
}
