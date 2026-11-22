package com.flowcortex.user.phone;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 403 with code {@code PHONE_VERIFICATION_REQUIRED}: the phone gate holds this caller. */
class PhoneVerificationRequiredException extends LocalizedStatusException {

    PhoneVerificationRequiredException() {
        super(HttpStatus.FORBIDDEN, new LocalizedMessage("phone.gate.required"));
        getBody().setProperty("code", "PHONE_VERIFICATION_REQUIRED");
    }
}
