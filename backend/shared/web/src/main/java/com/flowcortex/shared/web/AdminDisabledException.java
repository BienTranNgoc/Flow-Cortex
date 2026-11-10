package com.flowcortex.shared.web;

/**
 * Thrown when an admin whose console access was deactivated calls an admin route. Mapped by {@link
 * GlobalExceptionHandler} to a 403 problem of type {@code admin-disabled}, which the console tells
 * apart from a plain missing-permission 403.
 */
public class AdminDisabledException extends RuntimeException {

    public AdminDisabledException() {
        super("Admin access is deactivated");
    }
}
