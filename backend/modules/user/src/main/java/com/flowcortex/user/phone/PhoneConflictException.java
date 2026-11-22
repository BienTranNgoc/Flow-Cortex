package com.flowcortex.user.phone;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/**
 * 409 Problem Detail with a machine-readable {@code code} (e.g. {@code PHONE_ALREADY_VERIFIED}), for
 * the phone refusals the shared {@code ConflictException} cannot name.
 */
class PhoneConflictException extends LocalizedStatusException {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/conflict");

    PhoneConflictException(String code, LocalizedMessage detail) {
        super(HttpStatus.CONFLICT, detail);
        setType(TYPE);
        getBody().setProperty("code", code);
    }
}
