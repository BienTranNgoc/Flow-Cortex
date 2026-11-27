package com.flowcortex.llm.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.gateway.SlotChainResolver.Resolution;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SlotChainResolverTest {

    private static final List<String> CONFIGURED = List.of("lite", "flash");

    @Test
    void allActiveKeepsTheConfiguredOrder() {
        Resolution resolution = SlotChainResolver.resolve(CONFIGURED, () -> Map.of("lite", true, "flash", true));

        assertThat(resolution.models()).containsExactly("lite", "flash");
        assertThat(resolution.skipped()).isEmpty();
        assertThat(resolution.degradedReason()).isNull();
    }

    @Test
    void anInactiveModelIsLeftOutAndReported() {
        Resolution resolution = SlotChainResolver.resolve(CONFIGURED, () -> Map.of("lite", false, "flash", true));

        assertThat(resolution.models()).containsExactly("flash");
        assertThat(resolution.skipped()).containsExactly("lite");
        assertThat(resolution.degradedReason()).isNull();
    }

    @Test
    void aModelWithNoEntryStays() {
        Resolution resolution = SlotChainResolver.resolve(CONFIGURED, () -> Map.of("flash", false));

        assertThat(resolution.models()).containsExactly("lite");
        assertThat(resolution.skipped()).containsExactly("flash");

        Resolution noEntries = SlotChainResolver.resolve(CONFIGURED, Map::of);

        assertThat(noEntries.models()).containsExactly("lite", "flash");
        assertThat(noEntries.skipped()).isEmpty();
        assertThat(noEntries.degradedReason()).isNull();
    }

    @Test
    void everyModelInactiveServesTheFirstConfiguredModelAlone() {
        Resolution resolution = SlotChainResolver.resolve(CONFIGURED, () -> Map.of("lite", false, "flash", false));

        assertThat(resolution.models()).containsExactly("lite");
        assertThat(resolution.skipped()).isEmpty();
        assertThat(resolution.degradedReason()).isEqualTo("all_inactive");
    }

    @Test
    void aRegistryReadThatThrowsServesTheConfiguredChain() {
        Resolution resolution = SlotChainResolver.resolve(CONFIGURED, () -> {
            throw new IllegalStateException("database down");
        });

        assertThat(resolution.models()).containsExactly("lite", "flash");
        assertThat(resolution.skipped()).isEmpty();
        assertThat(resolution.degradedReason()).isEqualTo("registry_unreadable");
    }

    @Test
    void aSingleModelSlotWhoseModelIsInactiveStillServesIt() {
        Resolution resolution = SlotChainResolver.resolve(List.of("only"), () -> Map.of("only", false));

        assertThat(resolution.models()).containsExactly("only");
        assertThat(resolution.skipped()).isEmpty();
        assertThat(resolution.degradedReason()).isEqualTo("all_inactive");
    }
}
