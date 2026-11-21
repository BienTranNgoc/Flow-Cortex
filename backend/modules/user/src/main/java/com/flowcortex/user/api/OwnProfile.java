package com.flowcortex.user.api;

import java.time.Instant;

/**
 * The caller's own names and username.
 *
 * @param name                 display name
 * @param firstName            {@code null} when never set
 * @param lastName             {@code null} when never set
 * @param username             {@code null} until one is chosen
 * @param usernameNextChangeAt when the username may change again; {@code null} when it may change now
 */
public record OwnProfile(String name, String firstName, String lastName, String username,
        Instant usernameNextChangeAt) {
}
