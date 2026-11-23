package com.flowcortex.content.api;

import java.util.List;
import tools.jackson.databind.JsonNode;

/**
 * A kind of section a document may hold. Implement it as a bean to register a new type.
 * <p>
 * Paths returned by {@link #validate} and {@link #blankRequiredTexts} are relative to the
 * section's {@code data}, for example {@code body.vi}.
 */
public interface SectionType {

    /** The value of a section's {@code type}. */
    String type();

    /** The only {@code schemaVersion} of {@code data} this type accepts. */
    int schemaVersion();

    /** Every key {@code data} may hold; any other key is refused so it cannot reach readers. */
    List<String> dataFields();

    /** Refuses a malformed or over-long {@code data}; blank texts are valid in a draft. */
    List<FieldError> validate(JsonNode data);

    /** The paths of required texts that are blank in a (valid) {@code data}, in field order. */
    List<String> blankRequiredTexts(JsonNode data);

    /** The {@code data} fields holding a content library item id, checked against the library. */
    default List<String> mediaIdFields() {
        return List.of();
    }
}
