package com.flowcortex.entity.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import java.util.UUID;

/** Entity ids arrive as text, so a malformed one answers the same 404 as an unknown one instead of a 400. */
final class EntityIds {

    private EntityIds() {
    }

    static UUID parse(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            throw new NotFoundException(new LocalizedMessage("entity.notFound"));
        }
    }
}
