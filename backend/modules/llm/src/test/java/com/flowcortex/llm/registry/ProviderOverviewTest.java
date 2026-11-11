package com.flowcortex.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.llm.registry.ProviderOverview.ModelStatus;
import com.flowcortex.llm.registry.ProviderOverview.ProviderView;
import com.flowcortex.llm.registry.ProviderOverview.SlotModel;
import com.flowcortex.llm.registry.ProviderTestDecisionTest.StubProvider;
import com.flowcortex.llm.registry.RegisteredProvider.ConfigStatus;
import com.flowcortex.llm.registry.RegisteredProvider.SlotView;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class ProviderOverviewTest {

    private static StubProvider gemini(boolean configured, List<String> missing) {
        return new StubProvider("gemini", new ConfigStatus(configured, missing), true,
                List.of(new SlotView("dub-translate", List.of("flash-lite", "flash")),
                        new SlotView("dub-speech", List.of("flash-tts"))),
                id -> !id.endsWith("-tts"));
    }

    private static ProviderView onlyProvider(ProviderOverview overview) {
        assertThat(overview.categories()).hasSize(1);
        assertThat(overview.categories().get(0).providers()).hasSize(1);
        return overview.categories().get(0).providers().get(0);
    }

    @Test
    void groupsProvidersByCategory() {
        ProviderOverview overview = ProviderOverview.assemble(
                List.of(ProviderOverview.snapshot(gemini(true, List.of()))), Map.of());

        assertThat(overview.categories()).singleElement().satisfies(group -> {
            assertThat(group.category()).isEqualTo("text");
            assertThat(group.providers()).extracting(ProviderView::key).containsExactly("gemini");
        });
    }

    @Test
    void slotModelsAreMarkedByTheirEntryInSlotOrder() {
        ProviderView view = onlyProvider(ProviderOverview.assemble(
                List.of(ProviderOverview.snapshot(gemini(true, List.of()))),
                Map.of("flash-lite", true, "flash", false)));

        assertThat(view.slots()).extracting(slot -> slot.name()).containsExactly("dub-translate", "dub-speech");
        assertThat(view.slots().get(0).models()).containsExactly(
                new SlotModel("flash-lite", ModelStatus.ACTIVE.value()),
                new SlotModel("flash", ModelStatus.INACTIVE.value()));
    }

    @Test
    void aSlotModelWithNoEntryIsUnregisteredAndOneTheProviderRefusesIsNotApplicable() {
        ProviderView view = onlyProvider(ProviderOverview.assemble(
                List.of(ProviderOverview.snapshot(gemini(true, List.of()))), Map.of()));

        assertThat(view.slots().get(0).models()).extracting(SlotModel::status)
                .containsExactly("unregistered", "unregistered");
        assertThat(view.slots().get(1).models()).containsExactly(new SlotModel("flash-tts", "not_applicable"));
    }

    @Test
    void anUnconfiguredProviderNamesTheSettingAndStillShowsItsSlots() {
        ProviderView view = onlyProvider(ProviderOverview.assemble(
                List.of(ProviderOverview.snapshot(gemini(false, List.of("GEMINI_API_KEY")))), Map.of()));

        assertThat(view.configured()).isFalse();
        assertThat(view.missingSettings()).containsExactly("GEMINI_API_KEY");
        assertThat(view.slots()).hasSize(2);
    }

    @Test
    void slotModelKeysListsEachApplicableModelOnceAcrossProviders() {
        assertThat(ProviderOverview.slotModelKeys(List.of(gemini(true, List.of()), gemini(true, List.of()))))
                .containsExactlyInAnyOrder("flash-lite", "flash");
    }

    @Test
    void theSerializedOverviewCarriesNoSettingValue() {
        StubProvider provider = new StubProvider("gemini", new ConfigStatus(true, List.of()), true,
                List.of(new SlotView("dub-translate", List.of("flash"))), id -> true);

        String json = JsonMapper.builder().build().writeValueAsString(
                ProviderOverview.assemble(List.of(ProviderOverview.snapshot(provider)), Map.of()));

        assertThat(json).doesNotContain("test-key-123");
        assertThat(json).contains("\"configured\":true", "\"flash\"");
    }

    @Test
    void aRegistryRefusesTwoProvidersWithOneKeyNamingIt() {
        assertThatThrownBy(() -> new ProviderRegistry(List.of(gemini(true, List.of()), gemini(true, List.of()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gemini");
    }
}
