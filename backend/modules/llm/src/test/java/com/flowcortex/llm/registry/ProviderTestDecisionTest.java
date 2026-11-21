package com.flowcortex.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.registry.ProviderTestDecision.Kind;
import com.flowcortex.llm.registry.RegisteredProvider.ConfigStatus;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;

class ProviderTestDecisionTest {

    @Test
    void anAbsentProviderIsNotFound() {
        assertThat(ProviderTestDecision.decide(Optional.empty()).kind()).isEqualTo(Kind.NOT_FOUND);
    }

    @Test
    void anUnconfiguredProviderNamesWhatIsMissing() {
        ProviderTestDecision decision = ProviderTestDecision
                .decide(Optional.of(StubProvider.of(false, List.of("GEMINI_API_KEY"), true)));

        assertThat(decision.kind()).isEqualTo(Kind.MISSING_CONFIG);
        assertThat(decision.missingSettings()).containsExactly("GEMINI_API_KEY");
    }

    @Test
    void aConfiguredProviderWithoutAFreeCheckIsConfigOnly() {
        ProviderTestDecision decision = ProviderTestDecision
                .decide(Optional.of(StubProvider.of(true, List.of(), false)));

        assertThat(decision.kind()).isEqualTo(Kind.CONFIG_ONLY);
        assertThat(decision.missingSettings()).isEmpty();
    }

    @Test
    void aConfiguredProviderWithAFreeCheckIsLiveChecked() {
        assertThat(ProviderTestDecision.decide(Optional.of(StubProvider.of(true, List.of(), true))).kind())
                .isEqualTo(Kind.LIVE_CHECK);
    }

    @Test
    void missingConfigOutranksALiveCheck() {
        assertThat(ProviderTestDecision.decide(Optional.of(StubProvider.of(false, List.of("X"), true))).kind())
                .isEqualTo(Kind.MISSING_CONFIG);
    }

    /** A provider with fixed answers, shared with the overview tests. */
    static final class StubProvider implements RegisteredProvider {

        private final String key;
        private final ConfigStatus status;
        private final boolean liveCheckable;
        private final List<SlotView> slots;
        private final Predicate<String> accepts;

        StubProvider(String key, ConfigStatus status, boolean liveCheckable, List<SlotView> slots,
                Predicate<String> accepts) {
            this.key = key;
            this.status = status;
            this.liveCheckable = liveCheckable;
            this.slots = slots;
            this.accepts = accepts;
        }

        static StubProvider of(boolean configured, List<String> missing, boolean liveCheckable) {
            return new StubProvider("stub", new ConfigStatus(configured, missing), liveCheckable, List.of(),
                    id -> true);
        }

        @Override
        public String key() {
            return key;
        }

        @Override
        public ProviderCategory category() {
            return ProviderCategory.TEXT;
        }

        @Override
        public ConfigStatus configStatus() {
            return status;
        }

        @Override
        public boolean liveCheckable() {
            return liveCheckable;
        }

        @Override
        public ProbeResult liveCheck() {
            return ProbeResult.reachable(1);
        }

        @Override
        public boolean acceptsModelKey(String modelKey) {
            return accepts.test(modelKey);
        }

        @Override
        public VendorModels listModels() {
            return new VendorModels(List.of(), null);
        }

        @Override
        public ProbeResult testModel(String modelKey) {
            return ProbeResult.answered(1, 1);
        }

        @Override
        public List<SlotView> slots() {
            return slots;
        }
    }
}
