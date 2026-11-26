package com.flowcortex.entity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.flowcortex.entity.application.EntitySuggestionService.Counts;
import com.flowcortex.entity.domain.LabelSuggestions.LabelUse;
import com.flowcortex.entity.infrastructure.EntitySuggestionStore;
import com.flowcortex.entity.infrastructure.EntitySuggestionStore.Counted;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** How a suggestion request is reduced to a text key and which requests are refused before the store is read. */
class EntitySuggestionServiceTest {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private final EntitySuggestionStore store = mock(EntitySuggestionStore.class);
    private final EntitySuggestionService service = new EntitySuggestionService(store);
    private final UUID space = UUID.randomUUID();

    @Test
    void tagsAndCategoriesAskTheStoreForTheKeyOfTheQuery() {
        when(store.tags(space, "mua he", 10)).thenReturn(List.of(new Counted("Mùa Hè", 2)));
        when(store.categories(space, null, 50)).thenReturn(List.of(new Counted("Đồ uống", 3)));

        assertThat(service.tags(space, " Mùa  Hè ", 10)).containsExactly(new Counts("Mùa Hè", 2));
        assertThat(service.categories(space, "  ", 50)).containsExactly(new Counts("Đồ uống", 3));
    }

    @Test
    void aQueryOverTheCapIsRefusedBeforeTheStoreIsRead() {
        String tooLong = "x".repeat(101);

        assertThatThrownBy(() -> service.tags(space, tooLong, 10)).isInstanceOfSatisfying(
                EntityValidationException.class, e -> assertThat(e.errors()).containsOnlyKeys("q"));
        assertThatThrownBy(() -> service.categories(space, tooLong, 10)).isInstanceOf(EntityValidationException.class);
        assertThatThrownBy(() -> service.labels(space, null, tooLong, 8)).isInstanceOf(EntityValidationException.class);
        verify(store, never()).tags(space, null, 10);
    }

    @Test
    void labelsAreGroupedByKeyAndLimitedToARole() {
        when(store.labelUses(space, "product_photo")).thenReturn(List.of(
                new LabelUse("Mặt trước", "mat truoc", NOW),
                new LabelUse("Mặt sau", "mat sau", NOW.plusSeconds(1)),
                new LabelUse("mặt trước", "mat truoc", NOW.plusSeconds(2))));

        assertThat(service.labels(space, "product_photo", null, 8))
                .containsExactly(new Counts("mặt trước", 2), new Counts("Mặt sau", 1));
        assertThat(service.labels(space, "product_photo", "sau", 8)).containsExactly(new Counts("Mặt sau", 1));
    }

    @Test
    void aBlankRoleMeansEveryRoleAndAnUnknownOneIsRefused() {
        when(store.labelUses(space, null)).thenReturn(List.of());

        assertThat(service.labels(space, " ", null, 8)).isEmpty();
        assertThatThrownBy(() -> service.labels(space, "nope", null, 8)).isInstanceOfSatisfying(
                EntityValidationException.class,
                e -> assertThat(e.errors()).containsEntry("role", "entity.role.invalid"));
    }
}
