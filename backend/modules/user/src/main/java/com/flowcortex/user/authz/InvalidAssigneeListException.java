package com.flowcortex.user.authz;

import com.flowcortex.shared.web.LocalizedMessage;

/** The {@code for} parameter of the assignee list is missing or names a list that is not offered. */
class InvalidAssigneeListException extends RuntimeException {

    private final transient LocalizedMessage reason;

    InvalidAssigneeListException(LocalizedMessage reason) {
        super(reason.toString());
        this.reason = reason;
    }

    LocalizedMessage reason() {
        return reason;
    }
}
