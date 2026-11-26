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
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.asset.api.SpaceAssetSummary;
import com.flowcortex.asset.api.SpaceAssets;
import com.flowcortex.entity.application.EntityService.EntityPage;
import com.flowcortex.entity.application.EntityService.EntityPatch;
import com.flowcortex.entity.application.EntityService.EntityQuery;
import com.flowcortex.entity.application.EntityService.NewEntityInput;
import com.flowcortex.entity.domain.EntityChanges;
import com.flowcortex.entity.domain.EntityChanges.Change;
import com.flowcortex.entity.domain.EntitySnapshot;
import com.flowcortex.entity.domain.PartInput;
import com.flowcortex.entity.infrastructure.EntityStore;
import com.flowcortex.entity.infrastructure.EntityStore.EntityFilter;
import com.flowcortex.entity.infrastructure.EntityStore.EntityRow;
import com.flowcortex.entity.infrastructure.EntityStore.NewEntity;
import com.flowcortex.entity.infrastructure.EntityStore.PartRow;
import com.flowcortex.entity.infrastructure.HandleTakenException;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** The rule order of create, edit, delete and list around the store, with the store and the asset lookup mocked. */
class EntityServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private final EntityStore store = mock(EntityStore.class);
    private final SpaceAssets spaceAssets = mock(SpaceAssets.class);
    private final EntityService service = new EntityService(store, spaceAssets);

    private final UUID user = UUID.randomUUID();
    private final UUID space = UUID.randomUUID();

    @Test
    void createRefusesEveryBrokenFieldTogetherAndWritesNothing() {
        NewEntityInput input = new NewEntityInput("location", "   ", "Bad Handle", null, null,
                IntStream.range(0, 21).mapToObj(i -> "tag" + i).toList(), "x".repeat(41));

        assertThatThrownBy(() -> service.create(user, space, input)).isInstanceOfSatisfying(
                EntityValidationException.class, e -> assertThat(e.errors()).containsOnlyKeys(
                        "kind", "name", "handle", "tags", "categoryLabel"));
        verify(store, never()).insert(any());
    }

    @Test
    void createRefusesAFieldOfAnotherKind() {
        NewEntityInput input = new NewEntityInput("character", "Mai", null, Map.of("price", "1"), null, null, null);

        assertThatThrownBy(() -> service.create(user, space, input)).isInstanceOfSatisfying(
                EntityValidationException.class,
                e -> assertThat(e.errors()).containsEntry("fields", "entity.fields.unknownKey"));
    }

    @Test
    void createRefusesAnAssetTheSpaceDoesNotHold() {
        UUID asset = UUID.randomUUID();
        NewEntityInput input = new NewEntityInput("brand", "Nodi", null, null,
                List.of(new PartInput(asset, "logo", null)), null, null);

        assertThatThrownBy(() -> service.create(user, space, input)).isInstanceOfSatisfying(
                EntityValidationException.class,
                e -> assertThat(e.errors()).containsEntry("parts", "entity.parts.assetNotFound"));
        verify(spaceAssets).findInSpace(eq(space), any());
        verify(store, never()).insert(any());
    }

    @Test
    void aGivenHandleThatIsTakenIs409AtOnce() {
        when(store.insert(any())).thenReturn(Optional.empty());
        NewEntityInput input = new NewEntityInput("brand", "Nodi", "nodi", null, null, null, null);

        assertThatThrownBy(() -> service.create(user, space, input)).isInstanceOf(ConflictException.class);
        verify(store, times(1)).insert(any());
        verify(store, never()).liveHandlesWithBase(any(), anyString());
    }

    @Test
    void aGeneratedHandleIsRegeneratedAgainstAFreshReadAndGivesUpAfterThree() {
        when(store.insert(any())).thenReturn(Optional.empty());
        NewEntityInput input = new NewEntityInput("brand", "Nodi", null, null, null, null, null);

        assertThatThrownBy(() -> service.create(user, space, input)).isInstanceOf(ConflictException.class);
        verify(store, times(3)).insert(any());
        verify(store, times(3)).liveHandlesWithBase(space, "nodi");
    }

    @Test
    void aGeneratedHandleTakesTheNextNumberOnceTheWinnerIsVisible() {
        when(store.liveHandlesWithBase(space, "nodi")).thenReturn(Set.of()).thenReturn(Set.of("nodi"));
        when(store.insert(any())).thenReturn(Optional.empty())
                .thenAnswer(call -> Optional.of(call.<NewEntity>getArgument(0).id()));
        when(store.findLive(any(), eq(space))).thenAnswer(call -> Optional.of(row(call.getArgument(0), "nodi-2", 1)));

        EntityView created = service.create(user, space, new NewEntityInput("brand", "Nodi", null, null, null, null,
                null));

        ArgumentCaptor<NewEntity> inserted = ArgumentCaptor.forClass(NewEntity.class);
        verify(store, times(2)).insert(inserted.capture());
        assertThat(inserted.getAllValues()).extracting(NewEntity::handle).containsExactly("nodi", "nodi-2");
        assertThat(created.handle()).isEqualTo("nodi-2");
    }

    @Test
    void createWritesPartsTagsAndVersionOneWithTheSpacesTagSpelling() {
        UUID asset = UUID.randomUUID();
        when(spaceAssets.findInSpace(eq(space), any()))
                .thenReturn(List.of(new SpaceAssetSummary(asset, "image", "Logo", "image/png")));
        when(store.spaceTagLabels(eq(space), any())).thenReturn(Map.of("mua he", "Mùa Hè"));
        when(store.insert(any())).thenAnswer(call -> Optional.of(call.<NewEntity>getArgument(0).id()));
        when(store.findLive(any(), eq(space))).thenAnswer(call -> Optional.of(row(call.getArgument(0), "nodi", 1)));
        when(store.partsOf(any())).thenAnswer(call -> Map.of(call.<List<UUID>>getArgument(0).getFirst(),
                List.of(new PartRow(UUID.randomUUID(), asset, "logo", "Main", 0, NOW))));

        EntityView created = service.create(user, space, new NewEntityInput("brand", "Nodi", null,
                Map.of("voice", "warm"), List.of(new PartInput(asset, "logo", " Main ")), List.of("mùa hè"),
                "Đồ uống"));

        ArgumentCaptor<EntitySnapshot> snapshot = ArgumentCaptor.forClass(EntitySnapshot.class);
        verify(store).appendVersion(eq(created.id()), eq(1), snapshot.capture(), eq(List.of(Change.created())));
        assertThat(snapshot.getValue().tags()).containsExactly("Mùa Hè");
        assertThat(snapshot.getValue().categoryLabel()).isEqualTo("Đồ uống");
        assertThat(snapshot.getValue().parts()).containsExactly(new EntitySnapshot.Part(asset, "logo", "Main", 0));
        verify(store).replaceParts(created.id(), snapshot.getValue().parts());
        assertThat(created.parts()).extracting(EntityView.PartView::label).containsExactly("Main");
    }

    @Test
    void aPartWhoseAssetIsGoneIsLeftOutOfTheView() {
        UUID kept = UUID.randomUUID();
        UUID gone = UUID.randomUUID();
        UUID id = UUID.randomUUID();
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 1)));
        when(store.partsOf(List.of(id))).thenReturn(Map.of(id, List.of(
                new PartRow(UUID.randomUUID(), kept, "logo", null, 0, NOW),
                new PartRow(UUID.randomUUID(), gone, "logo", null, 1, NOW))));
        when(spaceAssets.findInSpace(eq(space), any()))
                .thenReturn(List.of(new SpaceAssetSummary(kept, "image", "Logo", "image/png")));

        EntityView view = service.get(space, id);

        assertThat(view.parts()).extracting(part -> part.asset().id()).containsExactly(kept);
    }

    @Test
    void getAndDeleteOfAnUnknownEntityAreNotFound() {
        UUID id = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(space, id)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.delete(user, space, id)).isInstanceOf(NotFoundException.class);
        when(store.trash(id, space, user)).thenReturn(true);
        service.delete(user, space, id);
        verify(store, times(2)).trash(id, space, user);
    }

    @Test
    void anEditOfAnUnknownEntityIsNotFound() {
        assertThatThrownBy(() -> service.update(space, UUID.randomUUID(), patch(1, "Name", null)))
                .isInstanceOf(NotFoundException.class);
    }

    @Test
    void aStaleBaseVersionIs409AndWritesNothing() {
        UUID id = locked("nodi", 3);

        assertThatThrownBy(() -> service.update(space, id, patch(2, "Renamed", null)))
                .isInstanceOf(ConflictException.class);
        verify(store, never()).update(any(), anyInt(), anyString(), anyString(), anyMap(), any());
        verify(store, never()).appendVersion(any(), anyInt(), any(), any());
    }

    @Test
    void anEditThatChangesNothingKeepsTheVersion() {
        UUID id = locked("nodi", 3);
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 3)));

        EntityView view = service.update(space, id, patch(3, "Nodi", null));

        assertThat(view.version()).isEqualTo(3);
        verify(store, never()).update(any(), anyInt(), anyString(), anyString(), anyMap(), any());
        verify(store, never()).appendVersion(any(), anyInt(), any(), any());
    }

    @Test
    void aRenameWritesTheNextVersionWithoutTouchingPartsOrTags() {
        UUID id = locked("nodi", 3);
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 4)));

        service.update(space, id, patch(3, "Nodi Studio", null));

        verify(store).update(eq(id), eq(4), eq("nodi"), eq("Nodi Studio"), anyMap(), eq("Lễ"));
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(store).appendVersion(eq(id), eq(4), any(EntitySnapshot.class), changes.capture());
        assertThat(changes.getValue()).extracting(Change::code).containsExactly(EntityChanges.RENAMED);
        verify(store, never()).replaceParts(any(), any());
        verify(store, never()).replaceTags(any(), any());
    }

    @Test
    void newTagsReplaceTheTagsAndAreRecordedAsAdded() {
        UUID id = locked("nodi", 1);
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 2)));

        service.update(space, id, new EntityPatch(1, null, null, null, null, null, List.of("Sale"), false, null));

        verify(store).replaceTags(eq(id), any());
        verify(store, never()).replaceParts(any(), any());
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(store).appendVersion(eq(id), eq(2), any(EntitySnapshot.class), changes.capture());
        assertThat(changes.getValue()).singleElement().satisfies(change -> {
            assertThat(change.code()).isEqualTo(EntityChanges.TAGS_CHANGED);
            assertThat(change.added()).containsExactly("Sale");
        });
    }

    @Test
    void newPartsReplaceTheParts() {
        UUID id = locked("nodi", 1);
        UUID asset = UUID.randomUUID();
        when(spaceAssets.findInSpace(eq(space), any()))
                .thenReturn(List.of(new SpaceAssetSummary(asset, "image", "Logo", "image/png")));
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 2)));

        service.update(space, id, new EntityPatch(1, null, null, null, null,
                List.of(new PartInput(asset, "logo", null)), null, false, null));

        verify(store).replaceParts(eq(id), eq(List.of(new EntitySnapshot.Part(asset, "logo", null, 0))));
        verify(store, never()).replaceTags(any(), any());
    }

    @Test
    void aCategoryChangesOnlyWhenTheRequestGaveOne() {
        UUID id = locked("nodi", 1);
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 2)));

        service.update(space, id, new EntityPatch(1, null, null, null, null, null, null, true, null));

        verify(store).update(eq(id), eq(2), eq("nodi"), eq("Nodi"), anyMap(), eq(null));
        ArgumentCaptor<List<Change>> changes = ArgumentCaptor.forClass(List.class);
        verify(store).appendVersion(eq(id), eq(2), any(EntitySnapshot.class), changes.capture());
        assertThat(changes.getValue()).extracting(Change::code).containsExactly(EntityChanges.CATEGORY_CHANGED);
    }

    @Test
    void aHandleAnotherLiveEntityHoldsIs409AndWritesNothing() {
        UUID id = locked("nodi", 1);
        when(store.handleHeldByAnother(space, "other", id)).thenReturn(true);

        assertThatThrownBy(() -> service.update(space, id,
                new EntityPatch(1, null, null, "other", null, null, null, false, null)))
                .isInstanceOf(ConflictException.class);
        verify(store, never()).update(any(), anyInt(), anyString(), anyString(), anyMap(), any());
    }

    @Test
    void aHandleLostToAConcurrentCreateIs409() {
        UUID id = locked("nodi", 1);
        doThrow(new HandleTakenException(new RuntimeException("duplicate")))
                .when(store).update(eq(id), eq(2), eq("other"), anyString(), anyMap(), any());

        assertThatThrownBy(() -> service.update(space, id,
                new EntityPatch(1, null, null, "other", null, null, null, false, null)))
                .isInstanceOf(ConflictException.class);
        verify(store, never()).appendVersion(any(), anyInt(), any(), any());
    }

    @Test
    void aKindChangeAndAMissingBaseVersionAreBothRefused() {
        UUID id = locked("nodi", 1);

        assertThatThrownBy(() -> service.update(space, id,
                new EntityPatch(null, "product", "x".repeat(201), "Bad", Map.of("price", "1"), null, null, false, null)))
                .isInstanceOfSatisfying(EntityValidationException.class, e -> assertThat(e.errors())
                        .containsOnlyKeys("baseVersion", "kind", "name", "handle", "fields"));
        when(store.findLive(id, space)).thenReturn(Optional.of(row(id, "nodi", 1)));
        assertThat(service.update(space, id,
                new EntityPatch(1, "brand", null, null, null, null, null, false, null)).version()).isEqualTo(1);
    }

    @Test
    void listRefusesAnUnknownKindAndALongQuery() {
        assertThatThrownBy(() -> service.list(space, new EntityQuery("location", "x".repeat(101), null, null), 0, 20))
                .isInstanceOfSatisfying(EntityValidationException.class,
                        e -> assertThat(e.errors()).containsOnlyKeys("kind", "q"));
    }

    @Test
    void listCountsEveryKindAndTotalsTheKindFilter() {
        UUID id = UUID.randomUUID();
        when(store.countByKind(eq(space), any(EntityFilter.class))).thenReturn(Map.of("brand", 1L, "product", 2L));
        when(store.page(eq(space), any(EntityFilter.class), eq(0), eq(20))).thenReturn(List.of(row(id, "nodi", 1)));

        EntityPage page = service.list(space, new EntityQuery("brand", " Đồ  Uống ", "Mùa Hè", "  "), 0, 20);

        assertThat(page.counts()).containsExactly(Map.entry("brand", 1L), Map.entry("product", 2L),
                Map.entry("character", 0L), Map.entry("voice", 0L));
        assertThat(page.page().total()).isEqualTo(1);
        assertThat(page.page().items()).extracting(EntityView::id).containsExactly(id);
        ArgumentCaptor<EntityFilter> filter = ArgumentCaptor.forClass(EntityFilter.class);
        verify(store).page(eq(space), filter.capture(), eq(0), eq(20));
        assertThat(filter.getValue()).isEqualTo(new EntityFilter("brand", "do uong", "mua he", null));
    }

    @Test
    void listWithoutMatchesReadsNoPage() {
        EntityPage page = service.list(space, new EntityQuery(null, null, null, null), 0, 20);

        assertThat(page.page().items()).isEmpty();
        assertThat(page.page().total()).isZero();
        verify(store, never()).page(any(), any(), anyInt(), anyInt());
    }

    private UUID locked(String handle, int version) {
        UUID id = UUID.randomUUID();
        when(store.lockLive(id, space)).thenReturn(Optional.of(row(id, handle, version)));
        return id;
    }

    private static EntityPatch patch(Integer baseVersion, String name, String handle) {
        return new EntityPatch(baseVersion, null, name, handle, null, null, null, false, null);
    }

    private EntityRow row(UUID id, String handle, int version) {
        return new EntityRow(id, space, "brand", handle, "Nodi", Map.of(), "Lễ", version, user, NOW, NOW);
    }
}
