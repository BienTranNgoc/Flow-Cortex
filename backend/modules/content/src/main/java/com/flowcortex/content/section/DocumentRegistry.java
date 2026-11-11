package com.flowcortex.content.section;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.SectionType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * The documents registered in code. Construction fails, so the application does not start, when a
 * definition is malformed, duplicated or names a section type no bean registers.
 */
@Component
public class DocumentRegistry {

    private static final Pattern SLUG = Pattern.compile("^[a-z0-9][a-z0-9-]{0,63}$");

    private final Map<String, ContentDocumentDefinition> bySlug = new LinkedHashMap<>();

    /** Definitions are optional beans: a deployment may register none. */
    @Autowired
    public DocumentRegistry(ObjectProvider<ContentDocumentDefinition> definitions, List<SectionType> sectionTypes) {
        this(definitions.orderedStream().toList(), sectionTypes);
    }

    public DocumentRegistry(List<ContentDocumentDefinition> definitions, List<SectionType> sectionTypes) {
        Set<String> knownTypes = sectionTypes.stream().map(SectionType::type).collect(Collectors.toSet());
        for (ContentDocumentDefinition definition : definitions) {
            String slug = definition.slug();
            if (slug == null || !SLUG.matcher(slug).matches()) {
                throw new IllegalStateException("Content document slug '" + slug
                        + "' must match " + SLUG.pattern());
            }
            if (bySlug.containsKey(slug)) {
                throw new IllegalStateException("Content document slug '" + slug + "' is registered twice");
            }
            for (String type : definition.sectionTypes()) {
                if (!knownTypes.contains(type)) {
                    throw new IllegalStateException("Content document '" + slug
                            + "' allows section type '" + type + "' that no SectionType bean registers");
                }
            }
            bySlug.put(slug, definition);
        }
    }

    public Optional<ContentDocumentDefinition> find(String slug) {
        return Optional.ofNullable(slug == null ? null : bySlug.get(slug));
    }

    /** All definitions in registration order. */
    public List<ContentDocumentDefinition> all() {
        return List.copyOf(bySlug.values());
    }
}
