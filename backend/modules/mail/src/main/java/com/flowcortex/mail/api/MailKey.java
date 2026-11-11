package com.flowcortex.mail.api;

import java.util.Arrays;
import java.util.Optional;

/**
 * The closed set of mails the backend sends. Each key declares its category, its subject and body
 * message codes and how many values a send carries.
 */
public enum MailKey {
    ACTIVATION("activation", MailCategory.TRANSACTIONAL, "email.confirm.subject", "email.confirm.body", 2),
    ACTIVATION_AFTER_RESET_REQUEST("activation-after-reset-request", MailCategory.TRANSACTIONAL,
            "email.confirm.subject", "email.confirmReset.body", 2),
    PASSWORD_SETUP_LINK("password-setup-link", MailCategory.TRANSACTIONAL, "email.passwordLink.subject",
            "email.passwordLink.body", 2),
    PASSWORD_SET("password-set", MailCategory.TRANSACTIONAL, "email.passwordSet.subject", "email.passwordSet.body", 0),
    RESET_PASSWORD("reset-password", MailCategory.TRANSACTIONAL, "email.passwordReset.subject",
            "email.passwordReset.body", 2),
    PASSWORD_CHANGED("password-changed", MailCategory.TRANSACTIONAL, "email.passwordChanged.subject",
            "email.passwordChanged.body", 1),
    GOOGLE_SIGN_IN_NOTICE("google-sign-in-notice", MailCategory.TRANSACTIONAL, "email.googleSignIn.subject",
            "email.googleSignIn.body", 1),
    SUPPORT_REQUEST_REPLIED("support-request-replied", MailCategory.TRANSACTIONAL, "support.mail.subject",
            "support.mail.body", 2),
    PHONE_CHANGED("phone-changed", MailCategory.TRANSACTIONAL, "email.phoneChanged.subject",
            "email.phoneChanged.body", 1);

    private final String id;
    private final MailCategory category;
    private final String subjectCode;
    private final String bodyCode;
    private final int valueCount;

    MailKey(String id, MailCategory category, String subjectCode, String bodyCode, int valueCount) {
        this.id = id;
        this.category = category;
        this.subjectCode = subjectCode;
        this.bodyCode = bodyCode;
        this.valueCount = valueCount;
    }

    /** The stable id used in logs and metrics. */
    public String id() {
        return id;
    }

    public MailCategory category() {
        return category;
    }

    public String subjectCode() {
        return subjectCode;
    }

    public String bodyCode() {
        return bodyCode;
    }

    /** How many values every send of this key carries, in the order the message codes index them. */
    public int valueCount() {
        return valueCount;
    }

    public static Optional<MailKey> fromId(String id) {
        return Arrays.stream(values()).filter(key -> key.id.equals(id)).findFirst();
    }
}
