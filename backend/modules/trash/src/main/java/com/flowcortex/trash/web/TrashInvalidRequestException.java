package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;

/**
 * A request that breaks a Trash rule on one field, answered 400 by {@link TrashProblemHandler}. The reason is a
 * message bundle key, put into the request's language by the handler.
 */
final class TrashInvalidRequestException extends RuntimeException {

    private final String field;

    TrashInvalidRequestException(String field) {
        super("invalid Trash request: " + field);
        this.field = field;
    }

    String field() {
        return field;
    }

    LocalizedMessage reason() {
        return switch (field) {
            case "type" -> new LocalizedMessage("trash.errors.type");
            case "q" -> new LocalizedMessage("trash.errors.q");
            case "sort" -> new LocalizedMessage("trash.errors.sort");
            case "page" -> new LocalizedMessage("trash.errors.page");
            case "size" -> new LocalizedMessage("trash.errors.size");
            case "items" -> new LocalizedMessage("trash.errors.items");
            case "next" -> new LocalizedMessage("trash.errors.next");
            default -> throw new IllegalStateException("no Trash field error for " + field);
        };
    }
}
