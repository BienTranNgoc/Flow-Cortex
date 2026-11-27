package com.flowcortex.user.phone;

import java.util.Locale;
import java.util.Objects;

/**
 * One SMS to send. {@code code} is set for {@link Kind#OTP} only; {@code requestId} is unique per
 * message so a carrier can recognise a re-send of the same one.
 */
record SmsMessage(String phoneE164, Kind kind, String code, Locale locale, String requestId) {

    enum Kind {
        OTP,
        PHONE_CHANGED,
        PHONE_CHANGE_REVERTED
    }

    SmsMessage {
        Objects.requireNonNull(phoneE164, "phoneE164");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(locale, "locale");
        Objects.requireNonNull(requestId, "requestId");
        if (kind == Kind.OTP && (code == null || code.isBlank())) {
            throw new IllegalArgumentException("A code message needs its code");
        }
        if (kind != Kind.OTP && code != null) {
            throw new IllegalArgumentException("A notice carries no code");
        }
    }

    /** Names the number by its last three digits and never the code, so a logged message leaks neither. */
    @Override
    public String toString() {
        return "SmsMessage[kind=" + kind + ", phone=" + PhoneNumbers.forLog(phoneE164) + "]";
    }
}
