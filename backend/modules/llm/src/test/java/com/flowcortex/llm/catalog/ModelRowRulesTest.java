package com.flowcortex.llm.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptor.Knob;
import com.flowcortex.llm.api.ModelDescriptor.Limits;
import com.flowcortex.llm.api.ModelDescriptor.Modality;
import com.flowcortex.llm.api.ModelDescriptor.Status;
import com.flowcortex.llm.api.ModelDescriptor.StructuredOutput;
import com.flowcortex.llm.api.ModelDescriptor.Thinking;
import com.flowcortex.llm.api.ModelDescriptors;
import com.flowcortex.llm.catalog.ModelRowRules.ModelRowDraft;
import com.flowcortex.llm.catalog.ModelRowRules.Operation;
import com.flowcortex.llm.registry.ProbeResult;
import com.flowcortex.llm.registry.RegisteredProvider;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class ModelRowRulesTest {

    private static final RegisteredProvider GEMINI = new FakeProvider("gemini");

    private static final Function<String, Optional<RegisteredProvider>> PROVIDERS =
            key -> "gemini".equals(key) ? Optional.of(GEMINI) : Optional.empty();

    private static final ModelDescriptors DESCRIPTORS = new FixtureDescriptors(List.of(
            descriptor("gemini-3.8-flash", "gemini", Status.STABLE),
            descriptor("gemini-old", "gemini", Status.DEPRECATED),
            descriptor("foreign-1", "other", Status.STABLE)));

    private static ModelRowDraft row(String modelKey, String providerKey, String mode, boolean enabled) {
        return new ModelRowDraft(modelKey, providerKey, null, null, mode, enabled, false);
    }

    private static Map<String, LocalizedMessage> create(ModelRowDraft draft) {
        return ModelRowRules.validate(draft, Operation.CREATE, PROVIDERS, DESCRIPTORS);
    }

    private static Map<String, LocalizedMessage> replace(ModelRowDraft draft) {
        return ModelRowRules.validate(draft, Operation.REPLACE, PROVIDERS, DESCRIPTORS);
    }

    @Test
    void aTextRowOfAnUnregisteredProviderIsAnErrorOnProviderKey() {
        Map<String, LocalizedMessage> errors = create(row("gpt-5", "openai", "text", true));

        assertThat(errors).containsOnlyKeys("providerKey");
        assertThat(errors.get("providerKey").code()).isEqualTo("llm.field.providerUnregistered");
    }

    @Test
    void aTextRowOfAProviderThatIsNotTextIsAnErrorOnProviderKey() {
        RegisteredProvider notText = new FakeProvider("gemini") {
            @Override
            public ProviderCategory category() {
                return null;
            }
        };

        Map<String, LocalizedMessage> errors = ModelRowRules.validate(
                row("gemini-3.8-flash", "gemini", "text", true), Operation.CREATE, key -> Optional.of(notText),
                DESCRIPTORS);

        assertThat(errors).containsOnlyKeys("providerKey");
        assertThat(errors.get("providerKey").code()).isEqualTo("llm.field.providerNotText");
    }

    @Test
    void aTextRowWithoutADescriptorIsAnErrorOnModelKey() {
        Map<String, LocalizedMessage> errors = create(row("gemini-1.0-pro", "gemini", "text", true));

        assertThat(errors).containsOnlyKeys("modelKey");
        assertThat(errors.get("modelKey").code()).isEqualTo("llm.field.modelNotDescribed");
    }

    @Test
    void aTextRowWhoseDescriptorBelongsToAnotherProviderIsAnErrorOnModelKey() {
        Map<String, LocalizedMessage> errors = create(row("foreign-1", "gemini", "text", true));

        assertThat(errors).containsOnlyKeys("modelKey");
        assertThat(errors.get("modelKey").code()).isEqualTo("llm.field.modelNotDescribed");
    }

    @Test
    void aTextRowWithADeprecatedDescriptorIsAnErrorOnModelKey() {
        Map<String, LocalizedMessage> errors = create(row("gemini-old", "gemini", "text", true));

        assertThat(errors).containsOnlyKeys("modelKey");
        assertThat(errors.get("modelKey").code()).isEqualTo("llm.field.modelDeprecated");
    }

    @Test
    void aDescribedTextRowHasNoError() {
        assertThat(create(row("gemini-3.8-flash", "gemini", "text", true))).isEmpty();
    }

    @Test
    void aMediaRowIsNotCheckedEvenWithAnUndescribedKeyAndAnUnregisteredProvider() {
        assertThat(create(row("seedance-1-pro", "byteplus", "audio", true))).isEmpty();
    }

    @Test
    void replacingATextRowToDisabledPassesWhenItsDescriptorIsDeprecated() {
        assertThat(replace(row("gemini-old", "gemini", "text", false))).isEmpty();
    }

    @Test
    void replacingATextRowToDisabledPassesWhenItsDescriptorIsGone() {
        assertThat(replace(row("gemini-1.0-pro", "gemini", "text", false))).isEmpty();
    }

    @Test
    void replacingATextRowToEnabledStillRefusesADeprecatedDescriptor() {
        Map<String, LocalizedMessage> errors = replace(row("gemini-old", "gemini", "text", true));

        assertThat(errors).containsOnlyKeys("modelKey");
        assertThat(errors.get("modelKey").code()).isEqualTo("llm.field.modelDeprecated");
    }

    @Test
    void creatingADisabledTextRowStillRunsTheFullRule() {
        assertThat(create(row("gemini-old", "gemini", "text", false))).containsOnlyKeys("modelKey");
    }

    private static ModelDescriptor descriptor(String id, String providerKey, Status status) {
        return new ModelDescriptor(id, providerKey, "family", List.of(Modality.TEXT), new Limits(1000, 100), true,
                StructuredOutput.RESPONSE_SCHEMA, new Thinking(Knob.NONE, List.of(), null), true, status, null,
                null, null);
    }

    private record FixtureDescriptors(List<ModelDescriptor> all) implements ModelDescriptors {

        @Override
        public Optional<ModelDescriptor> describe(String id) {
            return all.stream().filter(descriptor -> descriptor.id().equals(id)).findFirst();
        }
    }

    private static class FakeProvider implements RegisteredProvider {

        private final String key;

        FakeProvider(String key) {
            this.key = key;
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
            return true;
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
