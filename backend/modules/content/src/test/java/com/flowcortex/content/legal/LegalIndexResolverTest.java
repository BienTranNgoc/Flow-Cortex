package com.flowcortex.content.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.document.PublishedDocuments.PublishedCopy;
import com.flowcortex.content.legal.LegalIndexResolver.Resolution;
import com.flowcortex.content.legal.LegalIndexResolver.ResolvedEntry;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LegalIndexResolverTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Map<String, PublishedCopy> published = new HashMap<>();
    private final LegalIndexResolver resolver = new LegalIndexResolver(slug -> Optional.ofNullable(published.get(slug)));

    private static ObjectNode text(String vi, String en) {
        ObjectNode node = JSON.createObjectNode();
        node.put("vi", vi);
        node.put("en", en);
        return node;
    }

    private static PublishedCopy copy(String slug, JsonNode sections) {
        return new PublishedCopy(slug, 1, Instant.parse("2026-12-01T00:00:00Z"), sections, JSON.createObjectNode());
    }

    private void publishDocument(String slug, String titleVi, String titleEn) {
        ObjectNode data = JSON.createObjectNode();
        data.set("title", text(titleVi, titleEn));
        data.set("body", text("b", "b"));
        data.put("docVersion", "1.0");
        data.put("effectiveDate", "2026-12-01");
        published.put(slug, copy(slug, sections("legalDoc", data)));
    }

    private static ArrayNode sections(String type, ObjectNode data) {
        ObjectNode section = JSON.createObjectNode();
        section.put("type", type);
        section.put("schemaVersion", 1);
        section.set("data", data);
        return JSON.createArrayNode().add(section);
    }

    private static ObjectNode indexItem(String slug, boolean visible, String labelVi, String labelEn) {
        ObjectNode item = JSON.createObjectNode();
        item.put("slug", slug);
        item.put("visible", visible);
        if (labelVi != null || labelEn != null) {
            ObjectNode label = JSON.createObjectNode();
            if (labelVi != null) {
                label.put("vi", labelVi);
            }
            if (labelEn != null) {
                label.put("en", labelEn);
            }
            item.set("label", label);
        }
        return item;
    }

    private void publishIndex(ObjectNode... items) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        ObjectNode data = JSON.createObjectNode();
        data.set("items", array);
        published.put(LegalCatalogue.INDEX_SLUG, copy(LegalCatalogue.INDEX_SLUG, sections("legalIndex", data)));
    }

    private static List<String> slugs(Resolution resolution) {
        return resolution.items().stream().map(ResolvedEntry::slug).toList();
    }

    @Test
    void publishedIndexKeepsOrderAndShownItemsOnly() {
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        publishDocument("legal-terms", "Điều khoản", "Terms");
        publishDocument("legal-pricing", "Giá", "Pricing");
        publishIndex(indexItem("legal-pricing", true, null, null), indexItem("legal-terms", false, null, null),
                indexItem("legal-privacy", true, null, null));

        Resolution resolution = resolver.resolve();

        assertThat(slugs(resolution)).containsExactly("legal-pricing", "legal-privacy");
        assertThat(resolution.unresolved()).isEmpty();
    }

    @Test
    void overrideWinsPerLanguageAndBlankFallsBackToTitle() {
        publishDocument("legal-pricing", "Giá", "Pricing");
        publishIndex(indexItem("legal-pricing", true, "Bảng giá", "  "));

        assertThat(resolver.resolve().items()).containsExactly(new ResolvedEntry(
                "legal-pricing", "/legal/pricing", new LocalizedText("Bảng giá", "Pricing")));
    }

    @Test
    void unpublishedTargetIsLeftOutAndReported() {
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        publishIndex(indexItem("legal-pricing", true, null, null), indexItem("legal-privacy", true, null, null));

        Resolution resolution = resolver.resolve();

        assertThat(slugs(resolution)).containsExactly("legal-privacy");
        assertThat(resolution.unresolved()).containsExactly("legal-pricing");
    }

    @Test
    void targetWithoutAReadableTitleIsLeftOutAndReported() {
        published.put("legal-pricing", copy("legal-pricing", JSON.createArrayNode()));
        ObjectNode noTitle = JSON.createObjectNode();
        noTitle.put("docVersion", "1.0");
        published.put("legal-terms", copy("legal-terms", sections("legalDoc", noTitle)));
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        publishIndex(indexItem("legal-pricing", true, null, null), indexItem("legal-terms", true, null, null),
                indexItem("legal-privacy", true, null, null));

        Resolution resolution = resolver.resolve();

        assertThat(slugs(resolution)).containsExactly("legal-privacy");
        assertThat(resolution.unresolved()).containsExactly("legal-pricing", "legal-terms");
    }

    @Test
    void unpublishedIndexFallsBackToPublishedDocumentsInCatalogueOrder() {
        publishDocument("legal-terms", "Điều khoản", "Terms");
        publishDocument("legal-privacy", "Bảo mật", "Privacy");

        Resolution resolution = resolver.resolve();

        assertThat(slugs(resolution)).containsExactly("legal-privacy", "legal-terms");
        assertThat(resolution.items().get(0).label()).isEqualTo(new LocalizedText("Bảo mật", "Privacy"));
        assertThat(resolution.unresolved()).isEmpty();
    }

    @Test
    void nothingPublishedIsAnEmptyList() {
        Resolution resolution = resolver.resolve();

        assertThat(resolution.items()).isEmpty();
        assertThat(resolution.unresolved()).isEmpty();
    }

    @Test
    void indexWithAnUnreadableSectionFallsBack() {
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        ObjectNode notAnIndex = JSON.createObjectNode();
        notAnIndex.put("items", "legal-privacy");
        published.put(LegalCatalogue.INDEX_SLUG,
                copy(LegalCatalogue.INDEX_SLUG, sections("legalIndex", notAnIndex)));

        assertThat(slugs(resolver.resolve())).containsExactly("legal-privacy");

        published.put(LegalCatalogue.INDEX_SLUG, copy(LegalCatalogue.INDEX_SLUG, JSON.createArrayNode()));

        assertThat(slugs(resolver.resolve())).containsExactly("legal-privacy");
    }

    @Test
    void itemNoLongerInTheCatalogueIsLeftOutAndReported() {
        publishDocument("legal-cookies", "Cookie", "Cookies");
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        publishIndex(indexItem("legal-cookies", true, null, null), indexItem("legal-privacy", true, null, null));

        Resolution resolution = resolver.resolve();

        assertThat(slugs(resolution)).containsExactly("legal-privacy");
        assertThat(resolution.unresolved()).containsExactly("legal-cookies");
    }

    @Test
    void anItemThatIsNotReadableIsLeftOutWithoutBreakingTheRest() {
        publishDocument("legal-privacy", "Bảo mật", "Privacy");
        publishDocument("legal-terms", "Điều khoản", "Terms");
        ObjectNode visibleAsText = indexItem("legal-terms", true, null, null);
        visibleAsText.put("visible", "true");
        ArrayNode items = JSON.createArrayNode().add("legal-pricing").add(visibleAsText)
                .add(indexItem("legal-privacy", true, null, null));
        ObjectNode data = JSON.createObjectNode();
        data.set("items", items);
        published.put(LegalCatalogue.INDEX_SLUG, copy(LegalCatalogue.INDEX_SLUG, sections("legalIndex", data)));

        assertThat(slugs(resolver.resolve())).containsExactly("legal-privacy");
    }

    @Test
    void pathIsLegalSlashDoc() {
        publishDocument("legal-plan-change-renewal", "Đổi gói", "Plan change");

        assertThat(resolver.resolve().items()).extracting(ResolvedEntry::path)
                .containsExactly("/legal/plan-change-renewal");
    }
}
