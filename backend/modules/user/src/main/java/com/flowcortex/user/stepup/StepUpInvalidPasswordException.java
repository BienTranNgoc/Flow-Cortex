package com.flowcortex.user.stepup;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/**
 * 422 with code {@code STEP_UP_INVALID_PASSWORD}. Never 401: the member app reads a 401 as an
 * expired session, replays the request and signs the user out.
 */
class StepUpInvalidPasswordException extends LocalizedStatusException {

    StepUpInvalidPasswordException() {
        super(HttpStatus.UNPROCESSABLE_CONTENT, new LocalizedMessage("stepUp.invalidPassword"));
        getBody().setProperty("code", "STEP_UP_INVALID_PASSWORD");
    }
}
