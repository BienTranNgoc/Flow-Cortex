package com.flowcortex.user.infrastructure;

import java.util.UUID;

/** A user's id and display name, read without the rest of the row. */
public record UserName(UUID id, String name) {
}
