package com.flowcortex.billing.profile;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;

/**
 * What a billing profile must hold for an e-invoice to be issued to it, decided on the merged state: a stored
 * profile with the patch laid over it. An absent key keeps the stored value, JSON {@code null} clears it, a
 * value of the wrong JSON type is refused on its field. Strings are trimmed first and a blank optional one is
 * cleared; a {@code personal} profile never keeps a tax code and a taxed one never keeps a personal ID number.
 */
final class BillingProfileRules {

    /** The verdict: a profile ready to store, or the bundle key of the reason for every offending field. */
    sealed interface Outcome permits Valid, Invalid {
    }

    /** {@code makeDefault} is true only when the body said {@code isDefault: true}. */
    record Valid(ProfileFields fields, boolean makeDefault) implements Outcome {
    }

    record Invalid(Map<String, String> errors) implements Outcome {
    }

    static final String PERSONAL = "personal";
    static final String BUSINESS = "business";
    static final String HOUSEHOLD = "household";

    private static final Set<String> TYPES = Set.of(PERSONAL, BUSINESS, HOUSEHOLD);
    private static final Pattern TAX_CODE = Pattern.compile("^\\d{10}(-\\d{3})?$");
    private static final Pattern PERSONAL_ID = Pattern.compile("^\\d{12}$");
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final int MAX_NAME = 400;
    private static final int MAX_ADDRESS = 500;
    private static final int MAX_EMAIL = 255;
    private static final String NOT_TEXT = "billingProfile.field.notText";

    private BillingProfileRules() {
    }

    static Outcome create(JsonNode body) {
        return merge(ProfileFields.EMPTY, body);
    }

    static Outcome merge(ProfileFields stored, JsonNode patch) {
        Map<String, String> errors = new LinkedHashMap<>();
        String type = text(patch, "type", stored.type(), errors);
        String legalName = text(patch, "legalName", stored.legalName(), errors);
        String taxCode = text(patch, "taxCode", stored.taxCode(), errors);
        String personalId = text(patch, "personalIdNumber", stored.personalIdNumber(), errors);
        String address = text(patch, "address", stored.address(), errors);
        String email = text(patch, "email", stored.email(), errors);
        boolean makeDefault = makeDefault(patch, errors);

        if (type != null && TYPES.contains(type)) {
            taxCode = PERSONAL.equals(type) ? null : taxCode;
            personalId = PERSONAL.equals(type) ? personalId : null;
        }
        checkType(type, errors);
        checkName(legalName, errors);
        if (type != null && TYPES.contains(type)) {
            checkIdentity(type, taxCode, personalId, errors);
        }
        checkAddress(address, errors);
        checkEmail(email, errors);

        if (!errors.isEmpty()) {
            return new Invalid(errors);
        }
        return new Valid(new ProfileFields(type, legalName, taxCode, personalId, address, email), makeDefault);
    }

    private static String text(JsonNode patch, String name, String stored, Map<String, String> errors) {
        JsonNode node = patch.get(name);
        if (node == null) {
            return stored;
        }
        if (node.isNull()) {
            return null;
        }
        if (!node.isString()) {
            errors.put(name, NOT_TEXT);
            return stored;
        }
        String trimmed = node.stringValue().strip();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static boolean makeDefault(JsonNode patch, Map<String, String> errors) {
        JsonNode node = patch.get("isDefault");
        if (node == null || node.isNull()) {
            return false;
        }
        if (!node.isBoolean()) {
            errors.put("isDefault", "billingProfile.isDefault.invalid");
            return false;
        }
        return node.booleanValue();
    }

    private static void checkType(String type, Map<String, String> errors) {
        if ((type == null || !TYPES.contains(type)) && !errors.containsKey("type")) {
            errors.put("type", "billingProfile.type.invalid");
        }
    }

    private static void checkName(String legalName, Map<String, String> errors) {
        if (errors.containsKey("legalName")) {
            return;
        }
        if (legalName == null) {
            errors.put("legalName", "billingProfile.legalName.required");
        } else if (legalName.codePointCount(0, legalName.length()) > MAX_NAME) {
            errors.put("legalName", "billingProfile.legalName.tooLong");
        } else if (BuyerName.readable(legalName).isEmpty()) {
            errors.put("legalName", "billingProfile.legalName.unreadable");
        }
    }

    private static void checkIdentity(String type, String taxCode, String personalId, Map<String, String> errors) {
        if (PERSONAL.equals(type)) {
            if (!errors.containsKey("personalIdNumber")) {
                if (personalId == null) {
                    errors.put("personalIdNumber", "billingProfile.personalIdNumber.required");
                } else if (!PERSONAL_ID.matcher(personalId).matches()) {
                    errors.put("personalIdNumber", "billingProfile.personalIdNumber.invalid");
                }
            }
        } else if (!errors.containsKey("taxCode")) {
            if (taxCode == null) {
                errors.put("taxCode", "billingProfile.taxCode.required");
            } else if (!TAX_CODE.matcher(taxCode).matches()) {
                errors.put("taxCode", "billingProfile.taxCode.invalid");
            }
        }
    }

    private static void checkAddress(String address, Map<String, String> errors) {
        if (errors.containsKey("address")) {
            return;
        }
        if (address == null) {
            errors.put("address", "billingProfile.address.required");
        } else if (address.codePointCount(0, address.length()) > MAX_ADDRESS) {
            errors.put("address", "billingProfile.address.tooLong");
        }
    }

    private static void checkEmail(String email, Map<String, String> errors) {
        if (email == null || errors.containsKey("email")) {
            return;
        }
        if (email.length() > MAX_EMAIL) {
            errors.put("email", "billingProfile.email.tooLong");
        } else if (!EMAIL.matcher(email).matches()) {
            errors.put("email", "billingProfile.email.invalid");
        }
    }
}
