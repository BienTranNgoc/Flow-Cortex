package com.flowcortex.drive.domain;

import com.flowcortex.drive.domain.DriveRoles.ContentType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Create and update rules of a Drive item. Verdicts are field to message key; an empty map means valid. */
public final class DriveItemRules {

    public static final int NAME_MAX = 160;
    public static final int TEXT_MAX = 32_768;

    static final String NAME_INVALID = "drive.name.invalid";
    static final String ROLE_MISMATCH = "drive.role.mismatch";
    static final String TEXT_INVALID = "drive.text.invalid";
    static final String PAYLOAD_INVALID = "drive.payload.invalid";
    static final String VALUE_INVALID = "drive.value.invalid";

    /** What a create request carries; {@code hasUpload} and {@code hasRunOutput} say which source it names. */
    public record CreateInput(String name, String contentType, String semanticRole, String textValue,
            boolean hasUpload, boolean hasRunOutput) {
    }

    /** A patch: every {@code null} field means unchanged. */
    public record UpdateInput(String name, String semanticRole, String textValue) {
    }

    private DriveItemRules() {
    }

    /** The name as stored: trimmed; {@code null} stays {@code null}. */
    public static String normalizeName(String name) {
        return name == null ? null : name.strip();
    }

    public static Map<String, String> validateCreate(CreateInput input) {
        Map<String, String> errors = new LinkedHashMap<>();
        checkName(input.name(), errors);
        Optional<ContentType> type = ContentType.fromWire(input.contentType());
        if (type.isEmpty()) {
            errors.put("contentType", VALUE_INVALID);
            return errors;
        }
        checkRole(type.get(), input.semanticRole(), errors);
        if (type.get() == ContentType.TEXT) {
            checkText(input.textValue(), errors);
            if (input.hasUpload() || input.hasRunOutput()) {
                errors.put("payload", PAYLOAD_INVALID);
            }
        } else if (input.textValue() != null || input.hasUpload() == input.hasRunOutput()) {
            errors.put("payload", PAYLOAD_INVALID);
        }
        return errors;
    }

    /** {@code contentType} is the stored content type of the item being patched; it never changes. */
    public static Map<String, String> validateUpdate(String contentType, UpdateInput input) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (input.name() != null) {
            checkName(input.name(), errors);
        }
        Optional<ContentType> type = ContentType.fromWire(contentType);
        if (input.semanticRole() != null) {
            checkRole(type.orElse(null), input.semanticRole(), errors);
        }
        if (input.textValue() != null) {
            if (type.orElse(null) == ContentType.TEXT) {
                checkText(input.textValue(), errors);
            } else {
                errors.put("textValue", PAYLOAD_INVALID);
            }
        }
        return errors;
    }

    private static void checkName(String name, Map<String, String> errors) {
        String normalized = normalizeName(name);
        if (normalized == null || normalized.isEmpty() || normalized.length() > NAME_MAX) {
            errors.put("name", NAME_INVALID);
        }
    }

    private static void checkRole(ContentType type, String role, Map<String, String> errors) {
        Optional<ContentType> owner = DriveRoles.contentTypeOf(role);
        if (owner.isEmpty()) {
            errors.put("semanticRole", VALUE_INVALID);
        } else if (owner.get() != type) {
            errors.put("semanticRole", ROLE_MISMATCH);
        }
    }

    private static void checkText(String text, Map<String, String> errors) {
        if (text == null || text.isEmpty() || text.length() > TEXT_MAX) {
            errors.put("textValue", TEXT_INVALID);
        }
    }
}
