package com.flowcortex.user.pat;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 403 with code {@code PAT_NOT_ELIGIBLE}: only active, unlocked admin accounts manage tokens. */
class PatNotEligibleException extends LocalizedStatusException {

    PatNotEligibleException() {
        super(HttpStatus.FORBIDDEN, new LocalizedMessage("pat.notEligible"));
        getBody().setProperty("code", "PAT_NOT_ELIGIBLE");
    }
}
