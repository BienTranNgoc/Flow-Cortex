package com.flowcortex.chat.cards;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Names the card fields a Product Video validation refused, in the order the card shows them. */
public final class CardFields {

    public static final String PRODUCT_NAME = "productName";
    public static final String DESCRIPTION = "description";
    public static final String CTA = "cta";
    public static final String PRICE = "price";
    public static final String PROMOTION = "promotion";
    public static final String ATTACHMENTS = "attachments";

    private static final List<String> ORDER = List.of(PRODUCT_NAME, DESCRIPTION, CTA, PRICE, PROMOTION, ATTACHMENTS);

    private CardFields() {
    }

    /**
     * The refused fields. The draft reports one combined rule for optional fields that hold only blanks, so the
     * blank ones among {@code cta}, {@code price} and {@code promotion} are named from the values themselves.
     */
    public static List<String> refused(ConstraintViolationException e, String cta, String price, String promotion) {
        Set<String> fields = new LinkedHashSet<>();
        for (ConstraintViolation<?> violation : e.getConstraintViolations()) {
            switch (violation.getPropertyPath().toString()) {
                case "noBlankOptionalClaims" -> {
                    addIfBlank(fields, CTA, cta);
                    addIfBlank(fields, PRICE, price);
                    addIfBlank(fields, PROMOTION, promotion);
                }
                case "uploadIds", "distinctUploads" -> fields.add(ATTACHMENTS);
                default -> fields.add(violation.getPropertyPath().toString());
            }
        }
        List<String> sorted = new ArrayList<>(fields);
        sorted.sort((a, b) -> Integer.compare(rank(a), rank(b)));
        return List.copyOf(sorted);
    }

    private static void addIfBlank(Set<String> fields, String name, String value) {
        if (value != null && value.isBlank()) {
            fields.add(name);
        }
    }

    private static int rank(String field) {
        int index = ORDER.indexOf(field);
        return index < 0 ? ORDER.size() : index;
    }
}
