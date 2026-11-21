package com.flowcortex.notification.preferences;

import java.util.Map;

/**
 * The choices a PATCH sets; a null component is one the request did not name. The body is read as a
 * plain map because a typed body would silently ignore a field outside the three.
 */
record PreferencePatch(Boolean inApp, Boolean careEmail, Boolean marketingEmail) {

    static final String BODY = "body";

    /** Either a patch or the name of the first field that was refused ({@code body} for an empty request). */
    record Parsed(PreferencePatch patch, String errorField) {
    }

    static Parsed parse(Map<String, Object> body) {
        if (body == null || body.isEmpty()) {
            return refused(BODY);
        }
        Boolean inApp = null;
        Boolean careEmail = null;
        Boolean marketingEmail = null;
        for (Map.Entry<String, Object> entry : body.entrySet()) {
            if (!(entry.getValue() instanceof Boolean value)) {
                return refused(entry.getKey());
            }
            switch (entry.getKey()) {
                case "inApp" -> inApp = value;
                case "careEmail" -> careEmail = value;
                case "marketingEmail" -> marketingEmail = value;
                default -> {
                    return refused(entry.getKey());
                }
            }
        }
        return new Parsed(new PreferencePatch(inApp, careEmail, marketingEmail), null);
    }

    private static Parsed refused(String field) {
        return new Parsed(null, field);
    }
}
