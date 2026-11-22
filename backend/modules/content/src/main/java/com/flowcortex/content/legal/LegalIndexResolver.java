package com.flowcortex.content.legal;

import com.flowcortex.content.api.LocalizedText;
import com.flowcortex.content.document.PublishedDocuments;
import com.flowcortex.content.document.PublishedDocuments.PublishedCopy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Turns the published index and the published legal documents into the entries the member app lists. Reads
 * only published copies, so a draft never reaches it, and never throws on stored data it cannot read: such an
 * item is left out and reported.
 */
@Component
public class LegalIndexResolver {

    /** One link: the catalogue slug, its member page path and the label in each language. */
    public record ResolvedEntry(String slug, String path, LocalizedText label) {
    }

    /** The entries in display order and the slugs of shown items that could not be resolved. */
    public record Resolution(List<ResolvedEntry> items, List<String> unresolved) {
    }

    private final Function<String, Optional<PublishedCopy>> published;

    @Autowired
    LegalIndexResolver(PublishedDocuments published) {
        this(published::find);
    }

    public LegalIndexResolver(Function<String, Optional<PublishedCopy>> published) {
        this.published = published;
    }

    public Resolution resolve() {
        Optional<JsonNode> indexItems = publishedIndexItems();
        return indexItems.isPresent() ? fromIndex(indexItems.get()) : fromCatalogue();
    }

    private Resolution fromIndex(JsonNode items) {
        List<ResolvedEntry> entries = new ArrayList<>();
        List<String> unresolved = new ArrayList<>();
        for (JsonNode item : items) {
            JsonNode slugNode = item.path("slug");
            JsonNode visible = item.path("visible");
            if (!slugNode.isString() || !visible.isBoolean() || !visible.booleanValue()) {
                continue;
            }
            String slug = slugNode.stringValue();
            Optional<LocalizedText> title = LegalCatalogue.isLegalDocument(slug) ? titleOf(slug) : Optional.empty();
            if (title.isEmpty()) {
                unresolved.add(slug);
                continue;
            }
            entries.add(entry(slug, new LocalizedText(
                    override(item.path("label").path("vi"), title.get().vi()),
                    override(item.path("label").path("en"), title.get().en()))));
        }
        return new Resolution(entries, unresolved);
    }

    private Resolution fromCatalogue() {
        List<ResolvedEntry> entries = new ArrayList<>();
        for (String slug : LegalCatalogue.slugs()) {
            titleOf(slug).ifPresent(title -> entries.add(entry(slug, title)));
        }
        return new Resolution(entries, List.of());
    }

    private static ResolvedEntry entry(String slug, LocalizedText label) {
        return new ResolvedEntry(slug, LegalCatalogue.pathOf(slug), label);
    }

    private static String override(JsonNode label, String title) {
        return label.isString() && !label.stringValue().isBlank() ? label.stringValue().strip() : title;
    }

    /** The {@code items} array of the published index, empty when it is unpublished or unreadable. */
    private Optional<JsonNode> publishedIndexItems() {
        return sectionData(LegalCatalogue.INDEX_SLUG, LegalIndexSection.TYPE)
                .map(data -> data.path("items")).filter(JsonNode::isArray);
    }

    private Optional<LocalizedText> titleOf(String slug) {
        return sectionData(slug, LegalDocSection.TYPE).map(data -> data.path("title")).flatMap(title -> {
            JsonNode vi = title.path("vi");
            JsonNode en = title.path("en");
            boolean readable = vi.isString() && !vi.stringValue().isBlank() && en.isString()
                    && !en.stringValue().isBlank();
            return readable ? Optional.of(new LocalizedText(vi.stringValue(), en.stringValue())) : Optional.empty();
        });
    }

    /** The {@code data} of the first section of {@code type} in the published copy of {@code slug}. */
    private Optional<JsonNode> sectionData(String slug, String type) {
        return published.apply(slug).map(PublishedCopy::sections).filter(JsonNode::isArray).flatMap(sections -> {
            for (JsonNode section : sections) {
                if (type.equals(section.path("type").asString("")) && section.path("data").isObject()) {
                    return Optional.of(section.path("data"));
                }
            }
            return Optional.empty();
        });
    }
}
