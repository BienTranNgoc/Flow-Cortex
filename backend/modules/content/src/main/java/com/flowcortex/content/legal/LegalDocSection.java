package com.flowcortex.content.legal;

import com.flowcortex.content.api.FieldError;
import com.flowcortex.content.api.SectionType;
import com.flowcortex.content.section.DocumentValidator;
import com.flowcortex.content.section.LocalizedTexts;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/** One legal document: a title and a Markdown body in both languages, a version label and an effective date. */
@Component
public class LegalDocSection implements SectionType {

    public static final String TYPE = "legalDoc";
    public static final int MAX_TITLE_CHARS = DocumentValidator.MAX_SEO_TITLE_CHARS;
    public static final int MAX_DOC_VERSION_CHARS = 32;

    private static final Pattern DOC_VERSION = Pattern.compile("^[A-Za-z0-9._-]*$");
    private static final Pattern ISO_DATE = Pattern.compile("^[0-9]{4}-[0-9]{2}-[0-9]{2}$");

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
        return List.of("title", "body", "docVersion", "effectiveDate");
    }

    @Override
    public List<FieldError> validate(JsonNode data) {
        List<FieldError> errors = new ArrayList<>();
        LocalizedTexts.validate("title", data.get("title"), MAX_TITLE_CHARS, true, errors);
        LocalizedTexts.validate("body", data.get("body"), DocumentValidator.MAX_TEXT_CHARS, true, errors);
        validateDocVersion(data.get("docVersion"), errors);
        validateEffectiveDate(data.get("effectiveDate"), errors);
        return errors;
    }

    @Override
    public List<String> blankRequiredTexts(JsonNode data) {
        List<String> blank = new ArrayList<>();
        LocalizedTexts.collectBlank("title", data.get("title"), blank);
        LocalizedTexts.collectBlank("body", data.get("body"), blank);
        if (isBlank(data.get("docVersion"))) {
            blank.add("docVersion");
        }
        if (isBlank(data.get("effectiveDate"))) {
            blank.add("effectiveDate");
        }
        return blank;
    }

    private static void validateDocVersion(JsonNode node, List<FieldError> errors) {
        if (absent(node)) {
            errors.add(new FieldError("docVersion", FieldError.REQUIRED));
        } else if (!node.isString()) {
            errors.add(new FieldError("docVersion", FieldError.MALFORMED));
        } else if (!DOC_VERSION.matcher(node.stringValue()).matches()) {
            errors.add(new FieldError("docVersion", FieldError.FORMAT));
        } else if (node.stringValue().length() > MAX_DOC_VERSION_CHARS) {
            errors.add(new FieldError("docVersion", FieldError.TOO_LONG));
        }
    }

    private static void validateEffectiveDate(JsonNode node, List<FieldError> errors) {
        if (absent(node)) {
            errors.add(new FieldError("effectiveDate", FieldError.REQUIRED));
        } else if (!node.isString()) {
            errors.add(new FieldError("effectiveDate", FieldError.MALFORMED));
        } else if (!node.stringValue().isEmpty() && !isRealDate(node.stringValue())) {
            errors.add(new FieldError("effectiveDate", FieldError.DATE));
        }
    }

    private static boolean isRealDate(String value) {
        if (!ISO_DATE.matcher(value).matches()) {
            return false;
        }
        try {
            LocalDate.parse(value);
            return true;
        } catch (DateTimeException e) {
            return false;
        }
    }

    private static boolean absent(JsonNode node) {
        return node == null || node.isMissingNode() || node.isNull();
    }

    private static boolean isBlank(JsonNode node) {
        return node == null || !node.isString() || node.stringValue().isBlank();
    }
}
