package com.flowcortex.realtime.application;

import com.flowcortex.realtime.api.TopicAuthorizer;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Resolves the topics a request names into the canonical set a stream subscribes to. A topic is
 * valid only when it is {@code user} or a registered authorizer claims it, and every topic is
 * authorized before a stream opens; one refusal refuses the request.
 */
public final class TopicAccess {

    /** How a request's topics were judged. */
    public enum Status { OK, INVALID, NOT_FOUND }

    /** The verdict, with the canonical topics when it is {@link Status#OK}. */
    public record Outcome(Status status, Set<String> topics) {
    }

    private static final String USER_TOPIC = "user";
    // A parse guard, three times the longest topic any authorizer claims (chat:<uuid>, 41 characters).
    private static final Pattern WELL_FORMED = Pattern.compile("[A-Za-z0-9:_-]{1,128}");
    private static final Outcome INVALID = new Outcome(Status.INVALID, Set.of());
    private static final Outcome NOT_FOUND = new Outcome(Status.NOT_FOUND, Set.of());

    private TopicAccess() {
    }

    public static Outcome resolve(UUID userId, List<String> raw, int maxTopics, List<TopicAuthorizer> authorizers) {
        if (raw == null || raw.isEmpty()) {
            return INVALID;
        }
        // Insertion order is the request's order; a null authorizer marks the built-in user topic.
        Map<String, TopicAuthorizer> claimed = new LinkedHashMap<>();
        for (String topic : raw) {
            if (topic == null || !WELL_FORMED.matcher(topic).matches()) {
                return INVALID;
            }
            if (USER_TOPIC.equals(topic)) {
                claimed.put(USER_TOPIC + ":" + userId, null);
                continue;
            }
            Optional<TopicAuthorizer> owner = authorizers.stream().filter(candidate -> candidate.supports(topic)).findFirst();
            if (owner.isEmpty()) {
                return INVALID;
            }
            claimed.put(topic, owner.get());
        }
        if (claimed.size() > maxTopics) {
            return INVALID;
        }
        for (Map.Entry<String, TopicAuthorizer> entry : claimed.entrySet()) {
            TopicAuthorizer owner = entry.getValue();
            if (owner != null && !owner.authorize(userId, entry.getKey())) {
                return NOT_FOUND;
            }
        }
        return new Outcome(Status.OK, new LinkedHashSet<>(claimed.keySet()));
    }
}
