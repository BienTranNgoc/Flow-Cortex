package com.flowcortex.user.stepup;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 409 with code {@code STEP_UP_PASSWORD_REQUIRED}: the account has no password to check yet. */
class StepUpPasswordRequiredException extends LocalizedStatusException {

    StepUpPasswordRequiredException() {
        super(HttpStatus.CONFLICT, new LocalizedMessage("stepUp.passwordRequired"));
        getBody().setProperty("code", "STEP_UP_PASSWORD_REQUIRED");
    }
}
