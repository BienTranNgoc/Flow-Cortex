package com.flowcortex.llm.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.registry.ModelEntryRules.ModelDraft;
import com.flowcortex.llm.registry.ModelEntryRules.ModelEntry;
import com.flowcortex.llm.registry.RegisteredProvider.ConfigStatus;
import com.flowcortex.llm.registry.RegisteredProvider.ProviderCategory;
import com.flowcortex.llm.registry.RegisteredProvider.SlotView;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModels;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ModelEntryRulesTest {

    private static final ProviderRegistry REGISTRY = new ProviderRegistry(List.of(new FakeProvider()));

    private static ModelDraft draft(String modelKey, String providerKey) {
        return new ModelDraft(modelKey, providerKey, "Flash", null, null, null, null, null);
    }

    @Test
    void anUnregisteredProviderIsAnErrorOnProviderKey() {
        Map<String, LocalizedMessage> errors = ModelEntryRules.validate(draft("fake-1", "nope"), REGISTRY);

        assertThat(errors).containsOnlyKeys("providerKey");
        assertThat(errors.get("providerKey").code()).isEqualTo("llm.field.providerUnregistered");
    }

    @Test
    void anIdTheProviderRefusesIsAnErrorOnModelKey() {
        Map<String, LocalizedMessage> errors = ModelEntryRules.validate(draft("other-1", "fake"), REGISTRY);

        assertThat(errors).containsOnlyKeys("modelKey");
        assertThat(errors.get("modelKey").code()).isEqualTo("llm.field.modelKeyRejected");
    }

    @Test
    void anAcceptedDraftHasNoError() {
        assertThat(ModelEntryRules.validate(draft("fake-1", "fake"), REGISTRY)).isEmpty();
    }

    @Test
    void absentFieldsTakeTheirDefaults() {
        ModelEntry entry = ModelEntryRules.withDefaults(draft("fake-1", "fake"));

        assertThat(entry.maxOutputTokens()).isEqualTo(4096);
        assertThat(entry.supportsVision()).isFalse();
        assertThat(entry.supportsAudio()).isFalse();
        assertThat(entry.active()).isTrue();
        assertThat(entry.displayOrder()).isZero();
    }

    @Test
    void suppliedFieldsSurviveTheDefaults() {
        ModelEntry entry = ModelEntryRules.withDefaults(
                new ModelDraft("fake-1", "fake", "Flash", 512, true, true, false, (short) 7));

        assertThat(entry.maxOutputTokens()).isEqualTo(512);
        assertThat(entry.supportsVision()).isTrue();
        assertThat(entry.supportsAudio()).isTrue();
        assertThat(entry.active()).isFalse();
        assertThat(entry.displayOrder()).isEqualTo((short) 7);
    }

    private static final class FakeProvider implements RegisteredProvider {

        @Override
        public String key() {
            return "fake";
        }

        @Override
        public ProviderCategory category() {
            return ProviderCategory.TEXT;
        }

        @Override
        public ConfigStatus configStatus() {
            return new ConfigStatus(true, List.of());
        }

        @Override
        public boolean liveCheckable() {
            return false;
        }

        @Override
        public ProbeResult liveCheck() {
            return ProbeResult.notTested();
        }

        @Override
        public boolean acceptsModelKey(String modelKey) {
            return modelKey.startsWith("fake-");
        }

        @Override
        public VendorModels listModels() {
            return new VendorModels(List.of(), null);
        }

        @Override
        public ProbeResult testModel(String modelKey) {
            return ProbeResult.notTested();
        }

        @Override
        public List<SlotView> slots() {
            return List.of();
        }
    }
}
