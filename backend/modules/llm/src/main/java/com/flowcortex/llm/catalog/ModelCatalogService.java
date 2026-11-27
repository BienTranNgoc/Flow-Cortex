package com.flowcortex.llm.catalog;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.api.ModelDescriptors;
import com.flowcortex.llm.catalog.CatalogCoverage.Coverage;
import com.flowcortex.llm.catalog.CatalogModelRepository.ModelCount;
import com.flowcortex.llm.catalog.ModelRowRules.ModelRowDraft;
import com.flowcortex.llm.catalog.ModelRowRules.Operation;
import com.flowcortex.llm.registry.ProviderRegistry;
import com.flowcortex.llm.web.LlmProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The admin's capability and model catalog: validation through {@link ModelRowRules}, then one write per call.
 * Nothing at runtime reads the rows yet; they are display and curation data.
 */
@Service
public class ModelCatalogService {

    public static final int MAX_PAGE_SIZE = 50;

    private final CatalogCapabilityRepository capabilities;
    private final CatalogModelRepository models;
    private final ProviderRegistry providers;
    private final ModelDescriptors descriptors;
    private final DeclaredCapabilities declared;
    private final Clock clock;

    ModelCatalogService(CatalogCapabilityRepository capabilities, CatalogModelRepository models,
            ProviderRegistry providers, ModelDescriptors descriptors, DeclaredCapabilities declared, Clock clock) {
        this.capabilities = capabilities;
        this.models = models;
        this.providers = providers;
        this.descriptors = descriptors;
        this.declared = declared;
        this.clock = clock;
    }

    /** The display fields an admin may replace on a capability. */
    public record CapabilityDraft(String category, String uiGroup, String uiIcon) {
    }

    @Schema(name = "CatalogCapability")
    public record CapabilityView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String capability,
            @Schema(requiredMode = RequiredMode.REQUIRED) String category,
            @Schema(requiredMode = RequiredMode.REQUIRED) String uiGroup,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String uiIcon,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean registered,
            @Schema(requiredMode = RequiredMode.REQUIRED) long modelCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) long enabledModelCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    /** Every capability row, plus the declared capabilities that have no row. */
    @Schema(name = "CatalogCapabilityList")
    public record CapabilityList(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<CapabilityView> capabilities,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<String> uncatalogued) {
    }

    /** {@code descriptor} is set only on a {@code text} row whose model has one. */
    @Schema(name = "CatalogModel")
    public record ModelView(
            @Schema(requiredMode = RequiredMode.REQUIRED) String capability,
            @Schema(requiredMode = RequiredMode.REQUIRED) String modelKey,
            @Schema(requiredMode = RequiredMode.REQUIRED) String providerKey,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String vendorLabel,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) String makerLabel,
            @Schema(requiredMode = RequiredMode.REQUIRED) String mode,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean enabled,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean notImplemented,
            @Schema(requiredMode = RequiredMode.NOT_REQUIRED, nullable = true) ModelDescriptor descriptor,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {
    }

    @Transactional(readOnly = true)
    public CapabilityList capabilities() {
        List<CatalogCapability> rows = capabilities.findAllByOrderByCategoryAscCapabilityAsc();
        Map<String, ModelCount> counts = counts();
        Coverage coverage = CatalogCoverage.assemble(rows.stream().map(CatalogCapability::capability).toList(),
                declared.declared());
        return new CapabilityList(
                rows.stream().map(row -> capabilityView(row, coverage.isRegistered(row.capability()), counts)).toList(),
                coverage.uncatalogued());
    }

    @Transactional(readOnly = true)
    public CapabilityView capability(String capability) {
        return capabilityView(requireCapability(capability), declared.declared().contains(capability), counts());
    }

    @Transactional
    public CapabilityView replaceCapability(String capability, CapabilityDraft draft) {
        CatalogCapability row = requireCapability(capability);
        row.replace(draft.category(), draft.uiGroup(), draft.uiIcon(), clock.instant());
        return capabilityView(capabilities.save(row), declared.declared().contains(capability), counts());
    }

    @Transactional(readOnly = true)
    public PageView<ModelView> models(String capability, int page, int size) {
        requireCapability(capability);
        int cappedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<CatalogModel> found = models.findByCapabilityOrderByModelKeyAsc(capability,
                PageRequest.of(Math.max(0, page), cappedSize));
        return new PageView<>(found.getContent().stream().map(this::modelView).toList(), found.getNumber(),
                found.getSize(), found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public ModelView model(String capability, String modelKey) {
        return modelView(requireModel(capability, modelKey));
    }

    @Transactional
    public ModelView createModel(String capability, ModelRowDraft draft) {
        requireCapability(capability);
        validated(draft, Operation.CREATE);
        int created = models.insertIfAbsent(capability, draft.modelKey(), draft.providerKey(), draft.vendorLabel(),
                draft.makerLabel(), draft.mode(), draft.enabled(), draft.notImplemented(), clock.instant());
        if (created == 0) {
            throw new LlmProblemException(HttpStatus.CONFLICT, "catalog-model-exists",
                    new LocalizedMessage("llm.problem.catalogModelExists", draft.modelKey()));
        }
        return modelView(requireModel(capability, draft.modelKey()));
    }

    @Transactional
    public ModelView replaceModel(String capability, String modelKey, ModelRowDraft draft) {
        CatalogModel row = requireModel(capability, modelKey);
        validated(draft, Operation.REPLACE);
        row.replace(draft.providerKey(), draft.vendorLabel(), draft.makerLabel(), draft.mode(), draft.enabled(),
                draft.notImplemented(), clock.instant());
        return modelView(models.save(row));
    }

    @Transactional
    public void deleteModel(String capability, String modelKey) {
        models.delete(requireModel(capability, modelKey));
    }

    /** Every code-defined descriptor, ordered by id. */
    public List<ModelDescriptor> descriptors() {
        return descriptors.all();
    }

    private void validated(ModelRowDraft draft, Operation operation) {
        Map<String, LocalizedMessage> errors = ModelRowRules.validate(draft, operation, providers::find, descriptors);
        if (!errors.isEmpty()) {
            throw new LlmProblemException(HttpStatus.BAD_REQUEST, "catalog-model-invalid",
                    new LocalizedMessage("llm.problem.catalogModelInvalid")).withFieldErrors(errors);
        }
    }

    private Map<String, ModelCount> counts() {
        Map<String, ModelCount> byCapability = new HashMap<>();
        for (ModelCount count : models.countByCapability()) {
            byCapability.put(count.getCapability(), count);
        }
        return byCapability;
    }

    private CapabilityView capabilityView(CatalogCapability row, boolean registered, Map<String, ModelCount> counts) {
        ModelCount count = counts.get(row.capability());
        return new CapabilityView(row.capability(), row.category(), row.uiGroup(), row.uiIcon(), registered,
                count == null ? 0 : count.getTotal(), count == null ? 0 : count.getEnabled(), row.createdAt(),
                row.updatedAt());
    }

    private ModelView modelView(CatalogModel row) {
        ModelDescriptor descriptor = ModelRowRules.TEXT_MODE.equals(row.mode())
                ? descriptors.describe(row.modelKey()).orElse(null)
                : null;
        return new ModelView(row.capability(), row.modelKey(), row.providerKey(), row.vendorLabel(),
                row.makerLabel(), row.mode(), row.enabled(), row.notImplemented(), descriptor, row.createdAt(),
                row.updatedAt());
    }

    private CatalogCapability requireCapability(String capability) {
        return capabilities.findById(capability).orElseThrow(() -> capabilityNotFound(capability));
    }

    /** A missing row answers {@code capability-not-found} when the capability itself is absent. */
    private CatalogModel requireModel(String capability, String modelKey) {
        return models.findById(new CatalogModel.Key(capability, modelKey)).orElseThrow(() -> {
            if (!capabilities.existsById(capability)) {
                return capabilityNotFound(capability);
            }
            return new LlmProblemException(HttpStatus.NOT_FOUND, "catalog-model-not-found",
                    new LocalizedMessage("llm.problem.catalogModelNotFound", modelKey));
        });
    }

    private static LlmProblemException capabilityNotFound(String capability) {
        return new LlmProblemException(HttpStatus.NOT_FOUND, "capability-not-found",
                new LocalizedMessage("llm.problem.capabilityNotFound", capability));
    }
}
