package com.flowcortex.content.section;

import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.SectionType;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** A content library image or video with alternative text and an optional caption. */
@Component
public class MediaSection implements SectionType {

    public static final String TYPE = "media";

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public int schemaVersion() {
        return 1;
    }

    @Override
    public List<String> dataFields() {
        return List.of("mediaId", "alt", "caption");
    }

    @Override
    public List<FieldError> validate(JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        JsonNode mediaId = data.get("mediaId");
        if (mediaId == null || mediaId.isNull()) {
            errors.add(new FieldError("mediaId", FieldError.REQUIRED));
        } else if (!mediaId.isString() || !isUuid(mediaId.stringValue())) {
            errors.add(new FieldError("mediaId", FieldError.MALFORMED));
        }
        LocalizedTexts.validate("alt", data.get("alt"), DocumentValidator.MAX_TEXT_CHARS, true, errors);
        LocalizedTexts.validate("caption", data.get("caption"), DocumentValidator.MAX_TEXT_CHARS, false, errors);
        return errors;
    }

    @Override
    public List<String> blankRequiredTexts(JsonNode data) {
        List<String> blank = new ArrayList<>();
        LocalizedTexts.collectBlank("alt", data.get("alt"), blank);
        return blank;
    }

    @Override
    public List<String> mediaIdFields() {
        return List.of("mediaId");
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
