package com.flowcortex.content.section;

import com.flowcortex.content.api.ContentDocumentDefinition;
import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.SectionType;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** The one arbiter of whether a draft save is within bounds; pure apart from the section types it holds. */
@Component
public class DocumentValidator {

    public static final int MAX_SECTIONS = 100;
    public static final int MAX_TEXT_CHARS = 50_000;
    public static final int MAX_SEO_TITLE_CHARS = 200;
    public static final int MAX_SEO_DESCRIPTION_CHARS = 500;
    /** 900 KiB: stays under nginx's default 1 MB request body limit that fronts the admin console. */
    public static final int MAX_TOTAL_BYTES = 921_600;

    private static final List<String> SECTION_KEYS = List.of("type", "schemaVersion", "data");
    private static final List<String> SEO_KEYS = List.of("title", "description");

    private final Map<String, SectionType> types = new HashMap<>();

    public DocumentValidator(List<SectionType> sectionTypes) {
        sectionTypes.forEach(type -> types.put(type.type(), type));
    }

    /**
     * The well-formed library item ids the sections name, for the caller to look up before {@link #validate}.
     * Empty for input {@link #validate} refuses before it reads any id.
     */
    public Set<UUID> referencedMedia(ContentDocumentDefinition definition, JsonNode sections) {
        Set<UUID> ids = new HashSet<>();
        if (sections == null || !sections.isArray() || sections.size() > MAX_SECTIONS) {
            return ids;
        }
        for (JsonNode section : sections) {
            SectionType type = types.get(section.path("type").asString(""));
            JsonNode data = section.get("data");
            if (type == null || data == null || !data.isObject() || !definition.sectionTypes().contains(type.type())) {
                continue;
            }
            for (String field : type.mediaIdFields()) {
                JsonNode id = data.get(field);
                if (id != null && id.isString() && isUuid(id.stringValue())) {
                    ids.add(UUID.fromString(id.stringValue()));
                }
            }
        }
        return ids;
    }

    /**
     * @param knownMedia ids of the content library items the caller found for the media sections
     * @return every problem found, empty when the save may be stored
     */
    public List<FieldError> validate(
            ContentDocumentDefinition definition, JsonNode sections, JsonNode seoMeta, Set<UUID> knownMedia) {
        List<FieldError> errors = new ArrayList<>();
        if (sections == null || !sections.isArray()) {
            errors.add(new FieldError("sections", FieldError.MALFORMED));
            return errors;
        }
        if (sections.size() > MAX_SECTIONS) {
            errors.add(new FieldError("sections", FieldError.TOO_MANY));
            return errors;
        }
        if (serializedBytes(sections) + serializedBytes(seoMeta) > MAX_TOTAL_BYTES) {
            errors.add(new FieldError("sections", FieldError.TOO_LARGE));
            return errors;
        }
        for (int i = 0; i < sections.size(); i++) {
            validateSection(definition, "sections[" + i + "]", sections.get(i), knownMedia, errors);
        }
        validateSeo(seoMeta, errors);
        return errors;
    }

    private void validateSection(ContentDocumentDefinition definition, String path, JsonNode section,
            Set<UUID> knownMedia, List<FieldError> errors) {
        if (!section.isObject()) {
            errors.add(new FieldError(path, FieldError.MALFORMED));
            return;
        }
        LocalizedTexts.refuseUnknownKeys(path, section, SECTION_KEYS, errors);
        JsonNode typeNode = section.get("type");
        if (typeNode == null || !typeNode.isString()) {
            errors.add(new FieldError(path + ".type", FieldError.REQUIRED));
            return;
        }
        SectionType type = types.get(typeNode.stringValue());
        if (type == null || !definition.sectionTypes().contains(type.type())) {
            errors.add(new FieldError(path + ".type", FieldError.TYPE_NOT_ALLOWED));
            return;
        }
        JsonNode version = section.get("schemaVersion");
        if (version == null || !version.isIntegralNumber()) {
            errors.add(new FieldError(path + ".schemaVersion", FieldError.REQUIRED));
            return;
        }
        if (version.intValue() != type.schemaVersion()) {
            errors.add(new FieldError(path + ".schemaVersion", FieldError.UNSUPPORTED_VERSION));
            return;
        }
        JsonNode data = section.get("data");
        if (data == null || !data.isObject()) {
            errors.add(new FieldError(path + ".data", FieldError.REQUIRED));
            return;
        }
        LocalizedTexts.refuseUnknownKeys(path + ".data", data, type.dataFields(), errors);
        String dataPath = path + ".data.";
        for (FieldError error : type.validate(data)) {
            errors.add(new FieldError(dataPath + error.path(), error.messageKey()));
        }
        for (String field : type.mediaIdFields()) {
            JsonNode id = data.get(field);
            if (id != null && id.isString() && isUuid(id.stringValue())
                    && !knownMedia.contains(UUID.fromString(id.stringValue()))) {
                errors.add(new FieldError(dataPath + field, FieldError.MEDIA_NOT_FOUND));
            }
        }
    }

    private static void validateSeo(JsonNode seoMeta, List<FieldError> errors) {
        if (seoMeta != null && !seoMeta.isNull() && !seoMeta.isObject()) {
            errors.add(new FieldError("seoMeta", FieldError.MALFORMED));
            return;
        }
        if (seoMeta != null && seoMeta.isObject()) {
            LocalizedTexts.refuseUnknownKeys("seoMeta", seoMeta, SEO_KEYS, errors);
        }
        LocalizedTexts.validate("seoMeta.title", seoMeta == null ? null : seoMeta.get("title"),
                MAX_SEO_TITLE_CHARS, true, errors);
        LocalizedTexts.validate("seoMeta.description", seoMeta == null ? null : seoMeta.get("description"),
                MAX_SEO_DESCRIPTION_CHARS, true, errors);
    }

    private static int serializedBytes(JsonNode node) {
        return node == null ? 0 : node.toString().getBytes(StandardCharsets.UTF_8).length;
    }

    private static boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return value.length() == 36;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
