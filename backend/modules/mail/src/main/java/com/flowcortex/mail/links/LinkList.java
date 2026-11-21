package com.flowcortex.mail.links;

import com.flowcortex.mail.api.MailCategory;
import java.util.Optional;

/** A mailing list a member can leave through a link; the wire name is what the token signs and the table stores. */
public enum LinkList {
    CARE("care", "care_unsubscribed_at"),
    MARKETING("marketing", "marketing_unsubscribed_at");

    private final String wire;
    private final String column;

    LinkList(String wire, String column) {
        this.wire = wire;
        this.column = column;
    }

    public String wire() {
        return wire;
    }

    /** Column holding the unsubscribe instant; a constant of this enum, never user input. */
    String column() {
        return column;
    }

    /** The list a category's mail belongs to; transactional mail belongs to none. */
    public static Optional<LinkList> of(MailCategory category) {
        return switch (category) {
            case CARE -> Optional.of(CARE);
            case PROMOTIONAL -> Optional.of(MARKETING);
            case TRANSACTIONAL -> Optional.empty();
        };
    }
}
