package com.flowcortex.connector.api;

/**
 * A stored credential failed authentication or names an unknown key. Its message carries no token
 * and no key material; it is distinct from "not connected", which reads as an empty result.
 */
public class CredentialUnreadableException extends RuntimeException {

    public CredentialUnreadableException(String message) {
        super(message);
    }
}
