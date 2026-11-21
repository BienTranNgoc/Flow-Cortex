package com.flowcortex.user.api;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.net.URI;
import org.springframework.http.HttpStatus;

/** 403 Problem Detail with code {@code STEP_UP_REQUIRED}: the action needs a fresh, unspent step-up proof. */
public class StepUpRequiredException extends LocalizedStatusException {

    private static final URI TYPE = URI.create("https://flow-cortex.dev/problems/step-up-required");

    public StepUpRequiredException() {
        super(HttpStatus.FORBIDDEN, new LocalizedMessage("stepUp.required"));
        setType(TYPE);
        getBody().setProperty("code", "STEP_UP_REQUIRED");
    }
}
