package com.flowcortex.chat.cards;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The rules for a list of attached images, and reading it back from a stored message payload. */
public final class Attachments {

    private static final Logger LOG = LoggerFactory.getLogger(Attachments.class);

    private Attachments() {
    }

    /**
     * The list with every file name stripped, or empty when it is not acceptable: {@code min} to {@code max}
     * entries, no null entry or id, no repeated id, and every name 1 to {@link Attachment#MAX_FILE_NAME_CHARS}
     * characters once stripped. A {@code null} list counts as no attachments.
     */
    public static Optional<List<Attachment>> clean(List<Attachment> raw, int min, int max) {
        List<Attachment> given = raw == null ? List.of() : raw;
        if (given.size() < min || given.size() > max) {
            return Optional.empty();
        }
        Set<UUID> seen = new HashSet<>();
        List<Attachment> cleaned = new ArrayList<>();
        for (Attachment attachment : given) {
            if (attachment == null || attachment.uploadId() == null || attachment.fileName() == null
                    || !seen.add(attachment.uploadId())) {
                return Optional.empty();
            }
            String name = attachment.fileName().strip();
            if (name.isEmpty() || name.codePointCount(0, name.length()) > Attachment.MAX_FILE_NAME_CHARS) {
                return Optional.empty();
            }
            cleaned.add(new Attachment(attachment.uploadId(), name));
        }
        return Optional.of(List.copyOf(cleaned));
    }

    /** The {@code attachments} of a message payload; an unreadable or absent list is empty. */
    public static List<Attachment> fromPayload(JsonMapper jsonMapper, String payloadJson) {
        if (payloadJson == null) {
            return List.of();
        }
        try {
            return fromNode(jsonMapper.readTree(payloadJson).path("attachments"));
        } catch (JacksonException e) {
            LOG.warn("chat.attachments outcome=unreadable_payload", e);
            return List.of();
        }
    }

    /** The entries of a stored array of {@code {uploadId, fileName}}; an entry that is not one is skipped. */
    public static List<Attachment> fromNode(JsonNode list) {
        List<Attachment> attachments = new ArrayList<>();
        for (JsonNode node : list) {
            try {
                attachments.add(new Attachment(UUID.fromString(node.path("uploadId").asString()),
                        node.path("fileName").asString()));
            } catch (IllegalArgumentException e) {
                LOG.warn("chat.attachments outcome=unreadable_entry", e);
            }
        }
        return List.copyOf(attachments);
    }
}
