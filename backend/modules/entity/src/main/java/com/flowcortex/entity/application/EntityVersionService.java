package com.flowcortex.entity.application;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.asset.api.SpaceAssets;
import com.flowcortex.entity.application.EntityView.PartView;
import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntitySnapshot;
import com.flowcortex.entity.domain.EntityText;
import com.flowcortex.entity.domain.RestorePlan;
import com.flowcortex.entity.domain.TagLabels;
import com.flowcortex.entity.infrastructure.EntityStore;
import com.flowcortex.entity.infrastructure.EntityStore.EntityRow;
import com.flowcortex.entity.infrastructure.EntityVersionStore;
import com.flowcortex.entity.infrastructure.EntityVersionStore.VersionRow;
import com.flowcortex.entity.infrastructure.EntityVersionStore.VersionSummary;
import com.flowcortex.entity.infrastructure.HandleTakenException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The versions of an entity of a personal space. Restore is an edit: it locks the entity row, honours the base
 * version and the handle rule, and always writes a new version, so the history records that the member restored.
 */
@Service
public class EntityVersionService {

    private static final Logger LOG = LoggerFactory.getLogger(EntityVersionService.class);

    /** A listed version; {@code current} marks the entity's latest one. */
    public record VersionItem(int version, Instant createdAt, boolean current, List<Change> changes,
            int usedInRuns) {
    }

    /** One version read as an entity; {@code entity.version()} is that version's number. */
    public record VersionDetail(int version, Instant createdAt, boolean current, EntityView entity) {
    }

    /** A part of the restored version left out because its asset is no longer in the library. */
    public record DroppedPart(UUID assetId, String role, String label) {
    }

    /** The entity after a restore and the parts that could not come back. */
    public record RestoreResult(EntityView entity, List<DroppedPart> droppedParts) {
    }

    private final EntityStore store;
    private final EntityVersionStore versions;
    private final EntityService entities;
    private final SpaceAssets spaceAssets;

    public EntityVersionService(EntityStore store, EntityVersionStore versions, EntityService entities,
            SpaceAssets spaceAssets) {
        this.store = store;
        this.versions = versions;
        this.entities = entities;
        this.spaceAssets = spaceAssets;
    }

    @Transactional(readOnly = true)
    public PageView<VersionItem> list(UUID spaceId, UUID entityId, int page, int size) {
        EntityRow row = store.findLive(entityId, spaceId).orElseThrow(EntityVersionService::entityNotFound);
        List<VersionItem> items = versions.page(row.id(), page, size).stream()
                .map(version -> item(version, row.version()))
                .toList();
        return new PageView<>(items, page, size, versions.count(row.id()));
    }

    @Transactional(readOnly = true)
    public VersionDetail read(UUID spaceId, UUID entityId, int number) {
        EntityRow row = store.findLive(entityId, spaceId).orElseThrow(EntityVersionService::entityNotFound);
        VersionRow version = versions.find(row.id(), number).orElseThrow(EntityVersionService::versionNotFound);
        return new VersionDetail(version.version(), version.createdAt(), version.version() == row.version(),
                snapshotView(spaceId, row, version));
    }

    @Transactional
    public RestoreResult restore(UUID spaceId, UUID entityId, int number, Integer baseVersion) {
        EntityRow row = store.lockLive(entityId, spaceId).orElseThrow(EntityVersionService::entityNotFound);
        if (baseVersion == null) {
            throw new EntityValidationException(Map.of("baseVersion", "entity.baseVersion.invalid"));
        }
        VersionRow target = versions.find(row.id(), number).orElseThrow(EntityVersionService::versionNotFound);
        if (baseVersion != row.version()) {
            throw new ConflictException(new LocalizedMessage("entity.versionConflict"));
        }

        EntitySnapshot snapshot = target.snapshot();
        Set<UUID> assetsInSpace = spaceAssets.findInSpace(spaceId, snapshot.parts().stream()
                .map(EntitySnapshot.Part::assetId).distinct().toList()).stream()
                .map(SpaceAssetSummary::id)
                .collect(Collectors.toSet());
        List<String> keys = snapshot.tags().stream().map(EntityText::key).toList();
        List<String> tags = TagLabels.resolve(snapshot.tags(), store.spaceTagLabels(spaceId, keys)).stream()
                .map(TagLabels.Tag::label)
                .toList();
        RestorePlan plan = RestorePlan.of(snapshot.withTags(tags), assetsInSpace, number);
        EntitySnapshot next = plan.next();

        if (!next.handle().equals(row.handle()) && store.handleHeldByAnother(spaceId, next.handle(), row.id())) {
            throw handleTaken();
        }
        int version = row.version() + 1;
        try {
            store.update(row.id(), version, next.handle(), next.name(), next.fields(), next.categoryLabel());
        } catch (HandleTakenException e) {
            throw handleTaken();
        }
        store.replaceParts(row.id(), next.parts());
        store.replaceTags(row.id(), TagLabels.resolve(next.tags(), Map.of()));
        store.appendVersion(row.id(), version, next, plan.changes());
        List<DroppedPart> dropped = plan.dropped().stream()
                .map(part -> new DroppedPart(part.assetId(), part.role(), part.label()))
                .toList();
        return new RestoreResult(entities.get(spaceId, row.id()), dropped);
    }

    private static VersionItem item(VersionSummary version, int currentVersion) {
        return new VersionItem(version.version(), version.createdAt(), version.version() == currentVersion,
                version.changes(), version.usedInRuns());
    }

    /**
     * The stored content as an entity. A stored part has no row of its own, so its id is derived from the entity,
     * the version and its position: stable, and never equal to a live part's id.
     */
    private EntityView snapshotView(UUID spaceId, EntityRow row, VersionRow version) {
        EntitySnapshot snapshot = version.snapshot();
        Map<UUID, SpaceAssetSummary> assets = spaceAssets.findInSpace(spaceId, snapshot.parts().stream()
                .map(EntitySnapshot.Part::assetId).distinct().toList()).stream()
                .collect(Collectors.toMap(SpaceAssetSummary::id, Function.identity(), (a, b) -> a));
        List<PartView> parts = new ArrayList<>(snapshot.parts().size());
        for (EntitySnapshot.Part part : snapshot.parts()) {
            SpaceAssetSummary asset = assets.get(part.assetId());
            if (asset == null) {
                LOG.warn("Part {} of version {} of entity {} points at an asset that is no longer in the space",
                        part.sort(), version.version(), row.id());
                continue;
            }
            UUID id = UUID.nameUUIDFromBytes(
                    (row.id() + ":" + version.version() + ":" + part.sort()).getBytes(StandardCharsets.UTF_8));
            parts.add(new PartView(id, part.role(), part.label(), part.sort(), asset));
        }
        return new EntityView(row.id(), row.kind(), snapshot.handle(), snapshot.name(), snapshot.fields(),
                snapshot.categoryLabel(), snapshot.tags(), parts, version.version(), row.createdAt(),
                version.createdAt());
    }

    private static NotFoundException entityNotFound() {
        return new NotFoundException(new LocalizedMessage("entity.notFound"));
    }

    private static NotFoundException versionNotFound() {
        return new NotFoundException(new LocalizedMessage("entity.version.notFound"));
    }

    private static ConflictException handleTaken() {
        return new ConflictException(new LocalizedMessage("entity.handleTaken"));
    }
}
