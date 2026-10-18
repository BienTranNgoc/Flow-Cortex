package com.flowcortex.user.api;

import java.util.UUID;

/**
 * Published once an account can be used: on its first email confirmation, at creation when no
 * confirmation is needed (admin-created, Google sign-up, registration with confirmation off), when
 * a Google sign-in takes over an unconfirmed account, and when an admin reactivates a confirmed
 * account; never while the account is suspended. Published inside the transaction that
 * makes the account usable, so a listener that persists through the event publication registry is
 * retried if it fails after that commit. A listener must tolerate a second delivery.
 */
public record UserActivatedEvent(UUID userId) {
}
