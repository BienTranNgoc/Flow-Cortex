package com.flowcortex.user.phone;

/**
 * The three texts a Brandname carrier is registered to send. The carrier refuses any other text, so
 * the text is the same whatever the member's language.
 */
record SmsTemplates(String otp, String phoneChanged, String phoneChangeReverted) {

    static final String CODE_PLACEHOLDER = "{CODE}";
    static final int MAX_LENGTH = 160;

    private static final String OTP_PROPERTY = "flowcortex.sms.vietguys.templates.otp";
    private static final String PHONE_CHANGED_PROPERTY = "flowcortex.sms.vietguys.templates.phone-changed";
    private static final String PHONE_CHANGE_REVERTED_PROPERTY =
            "flowcortex.sms.vietguys.templates.phone-change-reverted";
    private static final String SAMPLE_CODE = "000000";

    /**
     * GSM 03.38 default alphabet (ETSI TS 123 038) without its escape position: every character costs
     * one septet, so 160 characters fit one SMS. The extension table (square and curly brackets, pipe,
     * tilde, caret, backslash, euro sign) is out.
     */
    private static final String GSM7_BASIC =
            "@£$¥èéùìòÇ\nØø\rÅå"
            + "Δ_ΦΓΛΩΠΨΣΘΞ"
            + "ÆæßÉ !\"#¤%&'()*+,-./0123456789:;<=>?"
            + "¡ABCDEFGHIJKLMNOPQRSTUVWXYZÄÖÑÜ§"
            + "¿abcdefghijklmnopqrstuvwxyzäöñüà";

    /** @throws IllegalStateException naming the property of the first template that cannot be sent as it is */
    static SmsTemplates validated(String otp, String phoneChanged, String phoneChangeReverted) {
        requireCodeTemplate(OTP_PROPERTY, otp);
        requireNoticeTemplate(PHONE_CHANGED_PROPERTY, phoneChanged);
        requireNoticeTemplate(PHONE_CHANGE_REVERTED_PROPERTY, phoneChangeReverted);
        return new SmsTemplates(otp, phoneChanged, phoneChangeReverted);
    }

    String render(SmsMessage message) {
        return switch (message.kind()) {
            case OTP -> otp.replace(CODE_PLACEHOLDER, message.code());
            case PHONE_CHANGED -> phoneChanged;
            case PHONE_CHANGE_REVERTED -> phoneChangeReverted;
        };
    }

    private static void requireCodeTemplate(String property, String template) {
        requireSet(property, template);
        if (occurrences(template, CODE_PLACEHOLDER) != 1) {
            throw new IllegalStateException(property + " must hold " + CODE_PLACEHOLDER + " exactly once");
        }
        requireSendable(property, template.replace(CODE_PLACEHOLDER, SAMPLE_CODE));
    }

    private static void requireNoticeTemplate(String property, String template) {
        requireSet(property, template);
        if (template.contains(CODE_PLACEHOLDER)) {
            throw new IllegalStateException(property + " must not hold " + CODE_PLACEHOLDER
                    + ", a notice carries no code and would send it literally");
        }
        requireSendable(property, template);
    }

    private static void requireSet(String property, String template) {
        if (template == null || template.isBlank()) {
            throw new IllegalStateException(property + " is not set");
        }
    }

    private static void requireSendable(String property, String text) {
        if (text.length() > MAX_LENGTH) {
            throw new IllegalStateException(property + " is longer than " + MAX_LENGTH + " characters");
        }
        if (!text.chars().allMatch(c -> GSM7_BASIC.indexOf(c) >= 0)) {
            throw new IllegalStateException(property + " holds a character outside the GSM-7 basic set");
        }
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + part.length())) {
            count++;
        }
        return count;
    }
}
