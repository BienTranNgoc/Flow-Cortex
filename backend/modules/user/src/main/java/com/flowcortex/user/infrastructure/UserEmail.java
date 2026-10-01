package com.flowcortex.user.infrastructure;

import java.util.UUID;

/** A user's id and email, read without the rest of the row. */
public record UserEmail(UUID id, String email) {
}
