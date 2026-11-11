package com.flowcortex.mail.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/** 404 for a token that does not verify, names no row or names a member who is gone: one answer for all three. */
class EmailLinkInvalidException extends LocalizedStatusException {

    EmailLinkInvalidException() {
        super(HttpStatus.NOT_FOUND, new LocalizedMessage("mail.link.invalid"));
    }
}
