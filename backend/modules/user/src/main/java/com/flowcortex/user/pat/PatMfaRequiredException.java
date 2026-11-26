package com.flowcortex.user.pat;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 403 with code {@code PAT_MFA_REQUIRED}: a token is minted only from a session that passed a second factor. */
class PatMfaRequiredException extends LocalizedStatusException {

    PatMfaRequiredException() {
        super(HttpStatus.FORBIDDEN, new LocalizedMessage("pat.mfaRequired"));
        getBody().setProperty("code", "PAT_MFA_REQUIRED");
    }
}
