package com.flowcortex.content.web;

import java.util.UUID;

/** A snapshot id that is not a UUID names no snapshot, so it is 404 like an unknown one rather than 400. */
final class SnapshotIds {

    private SnapshotIds() {
    }

    static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw ContentProblemException.documentNotFound();
        }
    }
}
