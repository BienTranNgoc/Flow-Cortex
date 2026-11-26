package com.flowcortex.entity.application;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.asset.api.SpaceAssets;
import com.flowcortex.entity.application.EntityView.PartView;
import com.flowcortex.entity.domain.EntityChanges;
import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntityKind;
import com.flowcortex.entity.domain.EntityRules;
import com.flowcortex.entity.domain.EntityRules.Checked;
import com.flowcortex.entity.domain.EntitySnapshot;
import com.flowcortex.entity.domain.EntityText;
import com.flowcortex.entity.domain.Handles;
import com.flowcortex.entity.domain.PartInput;
import com.flowcortex.entity.domain.RuleViolation;
import com.flowcortex.entity.domain.TagLabels;
import com.flowcortex.entity.infrastructure.EntityStore;
import com.flowcortex.entity.infrastructure.EntityStore.EntityFilter;
import com.flowcortex.entity.infrastructure.EntityStore.EntityRow;
import com.flowcortex.entity.infrastructure.EntityStore.NewEntity;
import com.flowcortex.entity.infrastructure.EntityStore.PartRow;
import com.flowcortex.entity.infrastructure.HandleTakenException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The entities of a personal space. Edits lock the entity row, so of two edits from the same version one wins and
 * the other is told to reload; a create takes its handle from a single insert that reports a taken one without
 * raising, so it can try the next generated handle in the same transaction.
 */
@Service
public class EntityService {

    /** Longest {@code q} a list or suggestion request may carry, in code points. */
    public static final int Q_MAX = 100;

    private static final Logger LOG = LoggerFactory.getLogger(EntityService.class);
    private static final int HANDLE_ATTEMPTS = 3;
    private static final String KIND = "kind";
    private static final String HANDLE = "handle";

    /** A create request after parsing; every rule is checked here. */
    public record NewEntityInput(String kind, String name, String handle, Map<String, Object> fields,
            List<PartInput> parts, List<String> tags, String categoryLabel) {
    }

    /**
     * An edit: every {@code null} stays as it is, except the category, which is changed only when
     * {@code categoryGiven} and then cleared by a {@code null} label.
     */
    public record EntityPatch(Integer baseVersion, String kind, String name, String handle, Map<String, Object> fields,
            List<PartInput> parts, List<String> tags, boolean categoryGiven, String categoryLabel) {
    }

    /** The list filters as sent; {@code null} or blank means "no filter". */
    public record EntityQuery(String kind, String q, String tag, String category) {
    }

    /** One page of entities and the matching count per kind, the kind filter not applied to the counts. */
    public record EntityPage(PageView<EntityView> page, Map<String, Long> counts) {
    }

    private final EntityStore store;
    private final SpaceAssets spaceAssets;

    public EntityService(EntityStore store, SpaceAssets spaceAssets) {
        this.store = store;
        this.spaceAssets = spaceAssets;
    }

    @Transactional
    public EntityView create(UUID userId, UUID spaceId, NewEntityInput input) {
        Map<String, String> errors = new LinkedHashMap<>();
        Optional<EntityKind> kind = EntityKind.parse(input.kind());
        if (kind.isEmpty()) {
            errors.put(KIND, "entity.kind.invalid");
        }
        String name = take(EntityRules.checkName(input.name()), errors, null);
        if (input.handle() != null && !Handles.valid(input.handle())) {
            errors.put(HANDLE, "entity.handle.invalid");
        }
        Map<String, Object> fields = kind.isEmpty() ? Map.of()
                : take(EntityRules.checkFields(kind.get(), input.fields()), errors, Map.of());
        List<EntitySnapshot.Part> parts = kind.isEmpty() ? List.of() : resolveParts(spaceId, kind.get(), input.parts(), errors);
        List<String> tags = resolveTags(spaceId, input.tags(), errors);
        String category = take(EntityRules.checkCategory(input.categoryLabel()), errors, null);
        if (!errors.isEmpty()) {
            throw new EntityValidationException(errors);
        }

        UUID id = UUID.randomUUID();
        String handle = insert(new NewEntity(id, spaceId, userId, kind.get().wire(), null, name, fields, category),
                input.handle());
        EntitySnapshot snapshot = new EntitySnapshot(name, handle, fields, category, tags, parts);
        store.replaceParts(id, parts);
        store.replaceTags(id, TagLabels.resolve(tags, Map.of()));
        store.appendVersion(id, 1, snapshot, List.of(Change.created()));
        return viewOf(spaceId, id);
    }

    @Transactional(readOnly = true)
    public EntityView get(UUID spaceId, UUID id) {
        return viewOf(spaceId, id);
    }

    @Transactional(readOnly = true)
    public EntityPage list(UUID spaceId, EntityQuery query, int page, int size) {
        Map<String, String> errors = new LinkedHashMap<>();
        String kind = null;
        if (query.kind() != null && !query.kind().isBlank()) {
            Optional<EntityKind> parsed = EntityKind.parse(query.kind());
            if (parsed.isPresent()) {
                kind = parsed.get().wire();
            } else {
                errors.put(KIND, "entity.kind.invalid");
            }
        }
        if (query.q() != null && query.q().codePointCount(0, query.q().length()) > Q_MAX) {
            errors.put("q", "entity.q.tooLong");
        }
        if (!errors.isEmpty()) {
            throw new EntityValidationException(errors);
        }
        EntityFilter filter = new EntityFilter(kind, keyOrNull(query.q()), keyOrNull(query.tag()),
                keyOrNull(query.category()));
        Map<String, Long> stored = store.countByKind(spaceId, filter);
        Map<String, Long> counts = new LinkedHashMap<>();
        for (EntityKind each : EntityKind.values()) {
            counts.put(each.wire(), stored.getOrDefault(each.wire(), 0L));
        }
        long total = kind == null ? counts.values().stream().mapToLong(Long::longValue).sum() : counts.get(kind);
        List<EntityView> items = total == 0 ? List.of() : views(spaceId, store.page(spaceId, filter, page, size));
        return new EntityPage(new PageView<>(items, page, size, total), counts);
    }

    @Transactional
    public EntityView update(UUID spaceId, UUID entityId, EntityPatch patch) {
        EntityRow row = store.lockLive(entityId, spaceId).orElseThrow(EntityService::notFound);
        EntityKind kind = EntityKind.parse(row.kind())
                .orElseThrow(() -> new IllegalStateException("Entity " + row.id() + " holds an unknown kind"));
        EntitySnapshot current = snapshotOf(row);

        Map<String, String> errors = new LinkedHashMap<>();
        if (patch.baseVersion() == null) {
            errors.put("baseVersion", "entity.baseVersion.invalid");
        }
        if (patch.kind() != null && !patch.kind().equals(row.kind())) {
            errors.put(KIND, "entity.kind.immutable");
        }
        EntitySnapshot next = current;
        if (patch.name() != null) {
            next = next.withName(take(EntityRules.checkName(patch.name()), errors, current.name()));
        }
        if (patch.handle() != null) {
            if (Handles.valid(patch.handle())) {
                next = next.withHandle(patch.handle());
            } else {
                errors.put(HANDLE, "entity.handle.invalid");
            }
        }
        if (patch.fields() != null) {
            next = next.withFields(take(EntityRules.checkFields(kind, patch.fields()), errors, current.fields()));
        }
        if (patch.parts() != null) {
            next = next.withParts(resolveParts(spaceId, kind, patch.parts(), errors));
        }
        if (patch.tags() != null) {
            next = next.withTags(resolveTags(spaceId, patch.tags(), errors));
        }
        if (patch.categoryGiven()) {
            next = next.withCategoryLabel(take(EntityRules.checkCategory(patch.categoryLabel()), errors,
                    current.categoryLabel()));
        }
        if (!errors.isEmpty()) {
            throw new EntityValidationException(errors);
        }
        if (patch.baseVersion() != row.version()) {
            throw new ConflictException(new LocalizedMessage("entity.versionConflict"));
        }
        if (!next.handle().equals(current.handle()) && store.handleHeldByAnother(spaceId, next.handle(), row.id())) {
            throw handleTaken();
        }

        List<Change> changes = EntityChanges.diff(current, next);
        if (changes.isEmpty()) {
            return viewOf(spaceId, row.id());
        }
        int version = row.version() + 1;
        try {
            store.update(row.id(), version, next.handle(), next.name(), next.fields(), next.categoryLabel());
        } catch (HandleTakenException e) {
            throw handleTaken();
        }
        if (hasChange(changes, EntityChanges.PARTS_CHANGED)) {
            store.replaceParts(row.id(), next.parts());
        }
        if (hasChange(changes, EntityChanges.TAGS_CHANGED)) {
            store.replaceTags(row.id(), TagLabels.resolve(next.tags(), Map.of()));
        }
        store.appendVersion(row.id(), version, next, changes);
        return viewOf(spaceId, row.id());
    }

    @Transactional
    public void delete(UUID userId, UUID spaceId, UUID id) {
        if (!store.trash(id, spaceId, userId)) {
            throw notFound();
        }
    }

    private String insert(NewEntity entity, String givenHandle) {
        if (givenHandle != null) {
            if (store.insert(withHandle(entity, givenHandle)).isEmpty()) {
                throw handleTaken();
            }
            return givenHandle;
        }
        String base = Handles.generate(entity.name(), Set.of());
        for (int attempt = 0; attempt < HANDLE_ATTEMPTS; attempt++) {
            String handle = Handles.generate(entity.name(), store.liveHandlesWithBase(entity.spaceId(), base));
            if (store.insert(withHandle(entity, handle)).isPresent()) {
                return handle;
            }
        }
        throw handleTaken();
    }

    private static NewEntity withHandle(NewEntity entity, String handle) {
        return new NewEntity(entity.id(), entity.spaceId(), entity.createdBy(), entity.kind(), handle, entity.name(),
                entity.fields(), entity.categoryLabel());
    }

    /** The parts as stored, in request order; a violation of the shape, the roles or the assets is added to {@code errors}. */
    private List<EntitySnapshot.Part> resolveParts(UUID spaceId, EntityKind kind, List<PartInput> input,
            Map<String, String> errors) {
        Checked<List<PartInput>> shapes = EntityRules.checkPartShapes(kind, input);
        List<PartInput> parts = take(shapes, errors, List.of());
        if (shapes.valid() && !parts.isEmpty()) {
            List<UUID> assetIds = parts.stream().map(PartInput::assetId).distinct().toList();
            Map<UUID, String> assetKinds = spaceAssets.findInSpace(spaceId, assetIds).stream()
                    .collect(Collectors.toMap(SpaceAssetSummary::id, SpaceAssetSummary::kind, (a, b) -> a));
            EntityRules.checkPartAssets(parts, assetKinds).ifPresent(v -> note(errors, v));
        }
        List<EntitySnapshot.Part> stored = new ArrayList<>(parts.size());
        for (int sort = 0; sort < parts.size(); sort++) {
            PartInput part = parts.get(sort);
            stored.add(new EntitySnapshot.Part(part.assetId(), part.role(), part.label(), sort));
        }
        return stored;
    }

    /** The tag labels, each key spelled as the space already spells it. */
    private List<String> resolveTags(UUID spaceId, List<String> input, Map<String, String> errors) {
        List<String> labels = take(EntityRules.checkTags(input), errors, List.of());
        List<String> keys = labels.stream().map(EntityText::key).toList();
        return TagLabels.resolve(labels, store.spaceTagLabels(spaceId, keys)).stream().map(TagLabels.Tag::label).toList();
    }

    private EntityView viewOf(UUID spaceId, UUID id) {
        EntityRow row = store.findLive(id, spaceId).orElseThrow(EntityService::notFound);
        return views(spaceId, List.of(row)).getFirst();
    }

    private List<EntityView> views(UUID spaceId, List<EntityRow> rows) {
        List<UUID> ids = rows.stream().map(EntityRow::id).toList();
        Map<UUID, List<PartRow>> parts = store.partsOf(ids);
        Map<UUID, List<String>> tags = store.tagsOf(ids);
        Set<UUID> assetIds = parts.values().stream().flatMap(Collection::stream).map(PartRow::assetId)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<UUID, SpaceAssetSummary> assets = spaceAssets.findInSpace(spaceId, assetIds).stream()
                .collect(Collectors.toMap(SpaceAssetSummary::id, Function.identity(), (a, b) -> a));
        return rows.stream()
                .map(row -> view(row, parts.getOrDefault(row.id(), List.of()), tags.getOrDefault(row.id(), List.of()),
                        assets))
                .toList();
    }

    private static EntityView view(EntityRow row, List<PartRow> partRows, List<String> tags,
            Map<UUID, SpaceAssetSummary> assets) {
        List<PartView> parts = new ArrayList<>(partRows.size());
        for (PartRow part : partRows) {
            SpaceAssetSummary asset = assets.get(part.assetId());
            if (asset == null) {
                LOG.warn("Part {} of entity {} points at an asset that is no longer in the space", part.id(), row.id());
                continue;
            }
            parts.add(new PartView(part.id(), part.role(), part.label(), part.sort(), asset));
        }
        return new EntityView(row.id(), row.kind(), row.handle(), row.name(), row.fields(), row.categoryLabel(), tags,
                parts, row.version(), row.createdAt(), row.updatedAt());
    }

    private EntitySnapshot snapshotOf(EntityRow row) {
        List<EntitySnapshot.Part> parts = store.partsOf(List.of(row.id())).getOrDefault(row.id(), List.of()).stream()
                .map(part -> new EntitySnapshot.Part(part.assetId(), part.role(), part.label(), part.sort()))
                .toList();
        List<String> tags = store.tagsOf(List.of(row.id())).getOrDefault(row.id(), List.of());
        return new EntitySnapshot(row.name(), row.handle(), row.fields(), row.categoryLabel(), tags, parts);
    }

    private static boolean hasChange(List<Change> changes, String code) {
        return changes.stream().anyMatch(change -> change.code().equals(code));
    }

    private static String keyOrNull(String text) {
        String key = EntityText.key(text);
        return key.isEmpty() ? null : key;
    }

    private static <T> T take(Checked<T> checked, Map<String, String> errors, T fallback) {
        if (checked.valid()) {
            return checked.value();
        }
        note(errors, checked.violation());
        return fallback;
    }

    private static void note(Map<String, String> errors, RuleViolation violation) {
        errors.putIfAbsent(violation.field(), violation.messageKey());
    }

    private static NotFoundException notFound() {
        return new NotFoundException(new LocalizedMessage("entity.notFound"));
    }

    private static ConflictException handleTaken() {
        return new ConflictException(new LocalizedMessage("entity.handleTaken"));
    }
}
