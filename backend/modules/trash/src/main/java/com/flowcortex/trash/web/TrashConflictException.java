package com.flowcortex.trash.web;

/**
 * A provider refused to restore or purge the one item of a request. The message key is the provider's own, so
 * {@link TrashProblemHandler} shows the key itself when the bundles have no text for it, instead of failing the answer.
 */
final class TrashConflictException extends RuntimeException {

    private final String messageKey;

    TrashConflictException(String messageKey) {
        super("Trash item refused: " + messageKey);
        this.messageKey = messageKey;
    }

    String messageKey() {
        return messageKey;
    }
}
