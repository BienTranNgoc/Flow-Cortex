package com.flowcortex.content.section;

import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.SectionType;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** A Markdown body in both languages. */
@Component
public class RichTextSection implements SectionType {

    public static final String TYPE = "richText";

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
        return List.of("body");
    }

    @Override
    public List<FieldError> validate(JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        LocalizedTexts.validate("body", data.get("body"), DocumentValidator.MAX_TEXT_CHARS, true, errors);
        return errors;
    }

    @Override
    public List<String> blankRequiredTexts(JsonNode data) {
        List<String> blank = new ArrayList<>();
        LocalizedTexts.collectBlank("body", data.get("body"), blank);
        return blank;
    }
}
