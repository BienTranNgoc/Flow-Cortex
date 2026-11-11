package com.flowcortex.user.pat;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 409 with code {@code PAT_LIMIT_REACHED}: the account already holds the maximum of live tokens. */
class PatLimitReachedException extends LocalizedStatusException {

    PatLimitReachedException() {
        super(HttpStatus.CONFLICT, new LocalizedMessage("pat.limitReached"));
        getBody().setProperty("code", "PAT_LIMIT_REACHED");
    }
}
