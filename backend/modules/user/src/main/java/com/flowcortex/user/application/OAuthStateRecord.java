package com.flowcortex.user.application;

import java.time.Instant;

/**
 * Server-side record for an in-flight Google OAuth {@code state} value. The
 * value is one-shot: {@code OAuthStateStore#consume} deletes the entry.
 */
public record OAuthStateRecord(String redirectUri, Instant createdAt, String ip) {
}
