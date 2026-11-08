package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 503 refusing a run start whose workflow has a dub step while dubbing is unavailable; {@code code} is {@code dub_disabled}. */
class DubDisabledException extends LocalizedStatusException {

    static final String CODE = "dub_disabled";

    DubDisabledException() {
        super(HttpStatus.SERVICE_UNAVAILABLE, new LocalizedMessage("workflow.dub.disabled"));
        getBody().setProperty("code", CODE);
    }
}
