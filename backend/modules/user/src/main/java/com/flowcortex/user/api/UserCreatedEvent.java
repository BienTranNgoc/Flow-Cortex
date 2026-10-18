package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Published inside the transaction that inserts a new account — self-registration, Google sign-in
 * and admin creation alike — so a listener that persists through the event publication registry
 * is retried if it fails after the account committed.
 */
public record UserCreatedEvent(UUID userId) {
}
