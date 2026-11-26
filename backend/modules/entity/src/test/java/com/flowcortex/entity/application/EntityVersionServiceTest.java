package com.flowcortex.entity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.asset.api.SpaceAssets;
import com.flowcortex.entity.application.EntityVersionService.DroppedPart;
import com.flowcortex.entity.application.EntityVersionService.RestoreResult;
import com.flowcortex.entity.application.EntityVersionService.VersionDetail;
import com.flowcortex.entity.application.EntityVersionService.VersionItem;
import com.flowcortex.entity.domain.EntityChanges;
import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntitySnapshot;
import com.flowcortex.entity.infrastructure.EntityStore;
import com.flowcortex.entity.infrastructure.EntityStore.EntityRow;
import com.flowcortex.entity.infrastructure.EntityVersionStore;
import com.flowcortex.entity.infrastructure.EntityVersionStore.VersionRow;
import com.flowcortex.entity.infrastructure.EntityVersionStore.VersionSummary;
import com.flowcortex.entity.infrastructure.HandleTakenException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.shared.web.PageView;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The order of the checks in list, read and restore around the stores, with the stores and the asset lookup mocked. */
class EntityVersionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private final EntityStore store = mock(EntityStore.class);
    private final EntityVersionStore versions = mock(EntityVersionStore.class);
    private final EntityService entities = mock(EntityService.class);
    private final SpaceAssets spaceAssets = mock(SpaceAssets.class);
    private final EntityVersionService service = new EntityVersionService(store, versions, entities, spaceAssets);

    private final UUID space = UUID.randomUUID();
    private final UUID entity = UUID.randomUUID();
    private final UUID kept = UUID.randomUUID();
    private final UUID gone = UUID.randomUUID();

    @Test
    void listMarksOnlyTheCurrentVersion() {
        when(store.findLive(entity, space)).thenReturn(Optional.of(row("nodi", 3)));
        when(versions.page(entity, 0, 20)).thenReturn(List.of(
                new VersionSummary(3, NOW, List.of(Change.of(EntityChanges.RENAMED)), 0),
                new VersionSummary(2, NOW, List.of(Change.created()), 4)));
        when(versions.count(entity)).thenReturn(3L);

        PageView<VersionItem> page = service.list(space, entity, 0, 20);

        assertThat(page.items()).extracting(VersionItem::current).containsExactly(true, false);
        assertThat(page.items()).extracting(VersionItem::usedInRuns).containsExactly(0, 4);
        assertThat(page.total()).isEqualTo(3);
    }

    @Test
    void listAndReadOfAnEntityOutsideTheSpaceAreNotFound() {
        when(store.findLive(entity, space)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.list(space, entity, 0, 20)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.read(space, entity, 1)).isInstanceOf(NotFoundException.class);
        verify(versions, never()).page(any(), anyInt(), anyInt());
    }

    @Test
    void readLeavesOutAPartWhoseAssetIsGoneAndKeepsTheStoredContent() {
        when(store.findLive(entity, space)).thenReturn(Optional.of(row("nodi-now", 2)));
        when(versions.find(entity, 1)).thenReturn(Optional.of(new VersionRow(1, NOW, snapshot("Nodi", "nodi"))));
        when(spaceAssets.findInSpace(eq(space), any())).thenReturn(List.of(asset(kept)));

        VersionDetail detail = service.read(space, entity, 1);

        assertThat(detail.current()).isFalse();
        assertThat(detail.entity().version()).isEqualTo(1);
        assertThat(detail.entity().name()).isEqualTo("Nodi");
        assertThat(detail.entity().handle()).isEqualTo("nodi");
        assertThat(detail.entity().parts()).extracting(part -> part.asset().id()).containsExactly(kept);
        assertThat(detail.entity().parts().getFirst().id()).isNotNull();
    }

    @Test
    void readOfAMissingVersionIsNotFound() {
        when(store.findLive(entity, space)).thenReturn(Optional.of(row("nodi", 1)));
        when(versions.find(entity, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.read(space, entity, 9)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoreWritesTheNextVersionFromTheOldOneAndReportsTheDroppedPart() {
        EntityView after = new EntityView(entity, "brand", "nodi", "Nodi", Map.of(), "Lễ", List.of("Sale"), List.of(), 3,
                NOW, NOW);
        when(store.lockLive(entity, space)).thenReturn(Optional.of(row("nodi-now", 2)));
        when(versions.find(entity, 1)).thenReturn(Optional.of(new VersionRow(1, NOW, snapshot("Nodi", "nodi"))));
        when(spaceAssets.findInSpace(eq(space), any())).thenReturn(List.of(asset(kept)));
        when(store.spaceTagLabels(eq(space), any())).thenReturn(Map.of("sale", "Sale"));
        when(entities.get(space, entity)).thenReturn(after);

        RestoreResult result = service.restore(space, entity, 1, 2);

        assertThat(result.entity()).isSameAs(after);
        assertThat(result.droppedParts()).containsExactly(new DroppedPart(gone, "back", "Rear"));
        verify(store).update(eq(entity), eq(3), eq("nodi"), eq("Nodi"), anyMap(), eq("Lễ"));
        ArgumentCaptor<EntitySnapshot> written = ArgumentCaptor.forClass(EntitySnapshot.class);
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(store).appendVersion(eq(entity), eq(3), written.capture(), changes.capture());
        assertThat(written.getValue().tags()).containsExactly("Sale");
        assertThat(written.getValue().parts()).extracting(EntitySnapshot.Part::assetId).containsExactly(kept);
        assertThat(written.getValue().parts()).extracting(EntitySnapshot.Part::sort).containsExactly(0);
        assertThat(changes.getValue()).containsExactly(Change.restored(1));
    }

    @Test
    void restoreRefusesAMissingBaseVersionAStaleOneAndAMissingVersionBeforeWriting() {
        when(store.lockLive(entity, space)).thenReturn(Optional.of(row("nodi-now", 2)));
        when(versions.find(entity, 1)).thenReturn(Optional.of(new VersionRow(1, NOW, snapshot("Nodi", "nodi"))));
        when(versions.find(entity, 9)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(space, entity, 1, null)).isInstanceOfSatisfying(
                EntityValidationException.class, e -> assertThat(e.errors()).containsKey("baseVersion"));
        assertThatThrownBy(() -> service.restore(space, entity, 1, 1)).isInstanceOf(ConflictException.class);
        assertThatThrownBy(() -> service.restore(space, entity, 9, 2)).isInstanceOf(NotFoundException.class);
        verify(store, never()).update(any(), anyInt(), anyString(), anyString(), anyMap(), any());
        verify(store, never()).appendVersion(any(), anyInt(), any(), any());
    }

    @Test
    void restoreOfAnUnknownEntityIsNotFound() {
        when(store.lockLive(entity, space)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.restore(space, entity, 1, 1)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void restoreIsRefusedWhenAnotherEntityHoldsTheHandleNow() {
        when(store.lockLive(entity, space)).thenReturn(Optional.of(row("nodi-now", 2)));
        when(versions.find(entity, 1)).thenReturn(Optional.of(new VersionRow(1, NOW, snapshot("Nodi", "nodi"))));
        when(spaceAssets.findInSpace(eq(space), any())).thenReturn(List.of());
        when(store.handleHeldByAnother(space, "nodi", entity)).thenReturn(true);

        assertThatThrownBy(() -> service.restore(space, entity, 1, 2)).isInstanceOf(ConflictException.class);
        verify(store, never()).update(any(), anyInt(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void restoreLosingTheHandleToAConcurrentCreateIsAConflict() {
        when(store.lockLive(entity, space)).thenReturn(Optional.of(row("nodi-now", 2)));
        when(versions.find(entity, 1)).thenReturn(Optional.of(new VersionRow(1, NOW, snapshot("Nodi", "nodi"))));
        when(spaceAssets.findInSpace(eq(space), any())).thenReturn(List.of());
        doThrow(new HandleTakenException(new RuntimeException("duplicate"))).when(store)
                .update(any(), anyInt(), anyString(), anyString(), anyMap(), any());

        assertThatThrownBy(() -> service.restore(space, entity, 1, 2)).isInstanceOf(ConflictException.class);
        verify(store, never()).appendVersion(any(), anyInt(), any(), any());
    }

    private EntityRow row(String handle, int version) {
        return new EntityRow(entity, space, "brand", handle, "Nodi now", Map.of(), "Lễ", version, UUID.randomUUID(),
                NOW, NOW);
    }

    private EntitySnapshot snapshot(String name, String handle) {
        return new EntitySnapshot(name, handle, Map.of("voice", "calm"), "Lễ", List.of("sale"), List.of(
                new EntitySnapshot.Part(kept, "logo", null, 0),
                new EntitySnapshot.Part(gone, "back", "Rear", 1)));
    }

    private static SpaceAssetSummary asset(UUID id) {
        return new SpaceAssetSummary(id, "image", "Asset", "image/png");
    }
}
