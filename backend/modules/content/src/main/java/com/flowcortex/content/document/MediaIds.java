package com.flowcortex.content.document;

import com.flowcortex.content.media.ContentMediaRepository;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** Which of the content library item ids a document names exist, read in one query. */
@Component
class MediaIds {

    private final ContentMediaRepository media;

    MediaIds(ContentMediaRepository media) {
        this.media = media;
    }

    Set<UUID> existing(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(media.findExistingIds(ids));
    }
}
