package com.flowcortex.user.oauth;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import org.springframework.http.HttpStatus;

/**
 * 400 problem for an authorization request that cannot be redirected anywhere (unknown client,
 * unregistered redirect URI) or, at grant time, carries an OAuth error the page should have redirected.
 */
class OAuthRequestRefusedException extends LocalizedStatusException {

    private OAuthRequestRefusedException(LocalizedMessage detail) {
        super(HttpStatus.BAD_REQUEST, detail);
    }

    static OAuthRequestRefusedException unknownClient() {
        return new OAuthRequestRefusedException(new LocalizedMessage("oauth.invalidClient"));
    }

    static OAuthRequestRefusedException unregisteredRedirectUri() {
        return new OAuthRequestRefusedException(new LocalizedMessage("oauth.invalidRedirectUri"));
    }

    /** The grant of a request whose check reported {@code error}; the property carries it for the caller. */
    static OAuthRequestRefusedException withError(String error) {
        OAuthRequestRefusedException exception =
                new OAuthRequestRefusedException(new LocalizedMessage("oauth.invalidRequest", error));
        exception.getBody().setProperty("error", error);
        return exception;
    }
}
