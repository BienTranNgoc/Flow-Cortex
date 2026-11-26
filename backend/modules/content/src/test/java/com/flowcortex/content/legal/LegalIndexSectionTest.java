package com.flowcortex.content.legal;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.content.api.FieldError;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

class LegalIndexSectionTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final LegalIndexSection section = new LegalIndexSection();

    private static ObjectNode item(String slug, boolean visible) {
        ObjectNode item = JSON.createObjectNode();
        item.put("slug", slug);
        item.put("visible", visible);
        return item;
    }

    private static ObjectNode withLabel(ObjectNode item, String key, Object value) {
        ObjectNode label = item.has("label") ? (ObjectNode) item.get("label") : JSON.createObjectNode();
        if (value instanceof String text) {
            label.put(key, text);
        } else {
            label.put(key, 1);
        }
        item.set("label", label);
        return item;
    }

    private static ObjectNode data(ObjectNode... items) {
        ArrayNode array = JSON.createArrayNode();
        for (ObjectNode item : items) {
            array.add(item);
        }
        ObjectNode data = JSON.createObjectNode();
        data.set("items", array);
        return data;
    }

    @Test
    void acceptsShownHiddenAndPartialLabels() {
        ObjectNode data = data(
                item("legal-privacy", true),
                item("legal-terms", false),
                withLabel(item("legal-pricing", true), "vi", "Bảng giá"),
                withLabel(withLabel(item("legal-payment", true), "vi", "a".repeat(200)), "en", ""));

        assertThat(section.validate(data)).isEmpty();
    }

    @Test
    void acceptsAnEmptyItemList() {
        assertThat(section.validate(data())).isEmpty();
    }

    @Test
    void refusesAnUnknownSlug() {
        assertThat(section.validate(data(item("legal-cookies", true))))
                .containsExactly(new FieldError("items[0].slug", FieldError.UNKNOWN_DOCUMENT));
    }

    @Test
    void refusesTheIndexOwnSlug() {
        assertThat(section.validate(data(item("legal-privacy", true), item(LegalCatalogue.INDEX_SLUG, true))))
                .containsExactly(new FieldError("items[1].slug", FieldError.UNKNOWN_DOCUMENT));
    }

    @Test
    void refusesARepeatedSlugAtTheSecondPosition() {
        assertThat(section.validate(data(
                item("legal-privacy", true), item("legal-terms", true), item("legal-privacy", false))))
                .containsExactly(new FieldError("items[2].slug", FieldError.DUPLICATE));
    }

    @Test
    void refusesMoreItemsThanLegalDocuments() {
        ObjectNode[] items = new ObjectNode[LegalCatalogue.DOCUMENTS.size() + 1];
        for (int i = 0; i < items.length; i++) {
            items[i] = item("legal-privacy", true);
        }

        assertThat(items).hasSize(12);
        assertThat(section.validate(data(items))).containsExactly(new FieldError("items", FieldError.TOO_MANY));
    }

    @Test
    void refusesANonBooleanVisible() {
        ObjectNode item = item("legal-privacy", true);
        item.put("visible", "yes");

        assertThat(section.validate(data(item)))
                .containsExactly(new FieldError("items[0].visible", FieldError.MALFORMED));
    }

    @Test
    void refusesAMissingVisible() {
        ObjectNode item = item("legal-privacy", true);
        item.remove("visible");

        assertThat(section.validate(data(item)))
                .containsExactly(new FieldError("items[0].visible", FieldError.REQUIRED));
    }

    @Test
    void refusesAMissingOrMalformedItemsArray() {
        assertThat(section.validate(JSON.createObjectNode()))
                .containsExactly(new FieldError("items", FieldError.REQUIRED));

        ObjectNode notAnArray = JSON.createObjectNode();
        notAnArray.put("items", "legal-privacy");
        assertThat(section.validate(notAnArray)).containsExactly(new FieldError("items", FieldError.MALFORMED));

        ObjectNode notAnObject = JSON.createObjectNode();
        notAnObject.set("items", JSON.createArrayNode().add("legal-privacy"));
        assertThat(section.validate(notAnObject)).containsExactly(new FieldError("items[0]", FieldError.MALFORMED));
    }

    @Test
    void refusesALabelOver200() {
        assertThat(section.validate(data(withLabel(item("legal-privacy", true), "vi", "a".repeat(201)))))
                .containsExactly(new FieldError("items[0].label.vi", FieldError.TOO_LONG));
    }

    @Test
    void refusesAnUnknownLabelKey() {
        assertThat(section.validate(data(withLabel(item("legal-privacy", true), "fr", "Confidentialité"))))
                .containsExactly(new FieldError("items[0].label.fr", FieldError.MALFORMED));
    }

    @Test
    void refusesAWrongTypedLabel() {
        ObjectNode notAnObject = item("legal-privacy", true);
        notAnObject.put("label", "Privacy");
        assertThat(section.validate(data(notAnObject)))
                .containsExactly(new FieldError("items[0].label", FieldError.MALFORMED));

        assertThat(section.validate(data(withLabel(item("legal-privacy", true), "en", 1))))
                .containsExactly(new FieldError("items[0].label.en", FieldError.MALFORMED));
    }

    @Test
    void refusesANullLabelOrLabelLanguage() {
        ObjectNode nullLabel = item("legal-privacy", true);
        nullLabel.putNull("label");
        assertThat(section.validate(data(nullLabel)))
                .containsExactly(new FieldError("items[0].label", FieldError.MALFORMED));

        ObjectNode nullLanguages = item("legal-privacy", true);
        nullLanguages.putObject("label").putNull("vi").putNull("en");
        assertThat(section.validate(data(nullLanguages))).containsExactly(
                new FieldError("items[0].label.vi", FieldError.MALFORMED),
                new FieldError("items[0].label.en", FieldError.MALFORMED));
    }

    @Test
    void refusesAnUnknownItemKey() {
        ObjectNode item = item("legal-privacy", true);
        item.put("extra", 1);

        assertThat(section.validate(data(item)))
                .containsExactly(new FieldError("items[0].extra", FieldError.MALFORMED));
    }

    @Test
    void hasNoBlankRequiredTexts() {
        assertThat(section.blankRequiredTexts(data())).isEqualTo(List.of());
        assertThat(section.blankRequiredTexts(data(withLabel(item("legal-privacy", true), "vi", "")))).isEmpty();
    }
}
