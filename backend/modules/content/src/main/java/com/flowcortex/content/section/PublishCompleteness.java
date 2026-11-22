package com.flowcortex.content.section;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.SectionType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The one arbiter of whether a stored draft may be published: every required text filled in both languages. */
@Component
public class PublishCompleteness {

    private final Map<String, SectionType> types = new HashMap<>();

    public PublishCompleteness(List<SectionType> sectionTypes) {
        sectionTypes.forEach(type -> types.put(type.type(), type));
    }

    /** The paths of every blank required text, in document order; empty when the draft is complete. */
    public List<String> missing(ContentDocumentDefinition definition, JsonNode sections, JsonNode seoMeta) {
        List<String> missing = new ArrayList<>();
        if (sections != null && sections.isArray()) {
            for (int i = 0; i < sections.size(); i++) {
                JsonNode section = sections.get(i);
                SectionType type = types.get(section.path("type").asString(""));
                JsonNode data = section.get("data");
                if (type == null || data == null || !definition.sectionTypes().contains(type.type())) {
                    continue;
                }
                for (String path : type.blankRequiredTexts(data)) {
                    missing.add("sections[" + i + "].data." + path);
                }
            }
        }
        LocalizedTexts.collectBlank("seoMeta.title", seoMeta == null ? null : seoMeta.get("title"), missing);
        return missing;
    }
}
