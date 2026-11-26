package com.flowcortex.entity.application;

import com.flowcortex.entity.domain.EntityKind;
import com.flowcortex.entity.domain.EntityText;
import com.flowcortex.entity.domain.KindRules;
import com.flowcortex.entity.domain.LabelSuggestions;
import com.flowcortex.entity.domain.LabelSuggestions.Suggestion;
import com.flowcortex.entity.infrastructure.EntitySuggestionStore;
import com.flowcortex.entity.infrastructure.EntitySuggestionStore.Counted;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Tags, categories and part labels the space's live entities already use, for the pickers of the entity form. */
@Service
public class EntitySuggestionService {

    private final EntitySuggestionStore store;

    public EntitySuggestionService(EntitySuggestionStore store) {
        this.store = store;
    }

    /** A suggested text and how many live entities (or parts) carry it. */
    public record Counts(String label, long count) {
    }

    @Transactional(readOnly = true)
    public List<Counts> tags(UUID spaceId, String q, int limit) {
        return store.tags(spaceId, queryKey(q), limit).stream().map(EntitySuggestionService::counts).toList();
    }

    @Transactional(readOnly = true)
    public List<Counts> categories(UUID spaceId, String q, int limit) {
        return store.categories(spaceId, queryKey(q), limit).stream().map(EntitySuggestionService::counts).toList();
    }

    /** Labels used on parts, grouped by text key; {@code role} limits them to parts of that role. */
    @Transactional(readOnly = true)
    public List<Counts> labels(UUID spaceId, String role, String q, int limit) {
        String roleFilter = role == null || role.isBlank() ? null : role;
        String key = queryKey(q);
        if (roleFilter != null && !knownRole(roleFilter)) {
            throw new EntityValidationException(Map.of("role", "entity.role.invalid"));
        }
        List<Suggestion> grouped = LabelSuggestions.group(store.labelUses(spaceId, roleFilter), key == null ? "" : key,
                limit);
        return grouped.stream().map(s -> new Counts(s.label(), s.count())).toList();
    }

    private static boolean knownRole(String role) {
        return Arrays.stream(EntityKind.values()).anyMatch(kind -> KindRules.partRoles(kind).contains(role));
    }

    private static String queryKey(String q) {
        if (q != null && q.codePointCount(0, q.length()) > EntityService.Q_MAX) {
            throw new EntityValidationException(Map.of("q", "entity.q.tooLong"));
        }
        String key = EntityText.key(q);
        return key.isEmpty() ? null : key;
    }

    private static Counts counts(Counted counted) {
        return new Counts(counted.label(), counted.count());
    }
}
