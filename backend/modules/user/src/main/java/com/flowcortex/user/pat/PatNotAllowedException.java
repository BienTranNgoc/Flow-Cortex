package com.flowcortex.user.pat;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 403 with code {@code PAT_NOT_ALLOWED}: a personal access token may not act on this route. */
class PatNotAllowedException extends LocalizedStatusException {

    PatNotAllowedException() {
        super(HttpStatus.FORBIDDEN, new LocalizedMessage("pat.notAllowed"));
        getBody().setProperty("code", "PAT_NOT_ALLOWED");
    }
}
