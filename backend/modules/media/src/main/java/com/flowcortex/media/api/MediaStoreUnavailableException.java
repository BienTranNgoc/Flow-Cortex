package com.flowcortex.media.api;

/** The object store refused or could not take a write the server made itself; nothing a member sent caused it. */
public class MediaStoreUnavailableException extends RuntimeException {

    public MediaStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
