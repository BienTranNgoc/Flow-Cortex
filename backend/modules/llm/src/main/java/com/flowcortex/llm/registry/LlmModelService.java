package com.flowcortex.llm.registry;

import com.flowcortex.llm.registry.ModelEntryRules.ModelDraft;
import com.flowcortex.llm.registry.ModelEntryRules.ModelEntry;
import com.flowcortex.llm.registry.RegisteredProvider.ConfigStatus;
import com.flowcortex.llm.web.LlmProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The admin's model registry: validation through {@link ModelEntryRules}, then one write per call. */
@Service
public class LlmModelService {

    public static final int MAX_PAGE_SIZE = 50;

    private final LlmModelRepository models;
    private final ProviderRegistry providers;
    private final Clock clock;

    LlmModelService(LlmModelRepository models, ProviderRegistry providers, Clock clock) {
        this.models = models;
        this.providers = providers;
        this.clock = clock;
    }

    @Schema(name = "AiModel")
    public record AiModel(
            @Schema(requiredMode = RequiredMode.REQUIRED) String modelKey,
            @Schema(requiredMode = RequiredMode.REQUIRED) String providerKey,
            @Schema(requiredMode = RequiredMode.REQUIRED) String displayName,
            @Schema(requiredMode = RequiredMode.REQUIRED) int maxOutputTokens,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean supportsVision,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean supportsAudio,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean active,
            @Schema(requiredMode = RequiredMode.REQUIRED) int displayOrder,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

        static AiModel of(LlmModel model) {
            return new AiModel(model.modelKey(), model.providerKey(), model.displayName(),
                    model.maxOutputTokens(), model.supportsVision(), model.supportsAudio(), model.active(),
                    model.displayOrder(), model.createdAt(), model.updatedAt());
        }
    }

    @Transactional(readOnly = true)
    public PageView<AiModel> page(int page, int size) {
        int cappedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        Page<LlmModel> found = models.findAllByOrderByDisplayOrderAscModelKeyAsc(
                PageRequest.of(Math.max(0, page), cappedSize));
        return new PageView<>(found.getContent().stream().map(AiModel::of).toList(), found.getNumber(),
                found.getSize(), found.getTotalElements());
    }

    @Transactional(readOnly = true)
    public AiModel get(String modelKey) {
        return AiModel.of(require(modelKey));
    }

    @Transactional
    public AiModel create(ModelDraft draft) {
        ModelEntry entry = validated(draft);
        int created = models.insertIfAbsent(entry.modelKey(), entry.providerKey(), entry.displayName(),
                entry.maxOutputTokens(), entry.supportsVision(), entry.supportsAudio(), entry.active(),
                entry.displayOrder(), clock.instant());
        if (created == 0) {
            throw new LlmProblemException(HttpStatus.CONFLICT, "llm-model-exists",
                    new LocalizedMessage("llm.problem.modelExists", entry.modelKey()));
        }
        return AiModel.of(require(entry.modelKey()));
    }

    @Transactional
    public AiModel replace(String modelKey, ModelDraft draft) {
        LlmModel model = require(modelKey);
        ModelEntry entry = validated(new ModelDraft(modelKey, draft.providerKey(), draft.displayName(),
                draft.maxOutputTokens(), draft.supportsVision(), draft.supportsAudio(), draft.active(),
                draft.displayOrder()));
        model.replace(entry.providerKey(), entry.displayName(), entry.maxOutputTokens(), entry.supportsVision(),
                entry.supportsAudio(), entry.active(), entry.displayOrder(), clock.instant());
        return AiModel.of(models.save(model));
    }

    @Transactional
    public void delete(String modelKey) {
        models.delete(require(modelKey));
    }

    /**
     * Not transactional: the provider call can take seconds and must not hold a database connection.
     * Nothing is sent when the provider lacks settings.
     */
    public ProbeResult test(String modelKey) {
        LlmModel model = require(modelKey);
        RegisteredProvider provider = providers.find(model.providerKey()).orElseThrow(
                () -> new LlmProblemException(HttpStatus.NOT_FOUND, "ai-provider-not-found",
                        new LocalizedMessage("llm.problem.providerNotFound", model.providerKey())));
        ConfigStatus config = provider.configStatus();
        if (!config.configured()) {
            return ProbeResult.missingConfig(config.missingSettings());
        }
        return provider.testModel(model.modelKey());
    }

    private ModelEntry validated(ModelDraft draft) {
        Map<String, LocalizedMessage> errors = ModelEntryRules.validate(draft, providers);
        if (!errors.isEmpty()) {
            throw new LlmProblemException(HttpStatus.BAD_REQUEST, "llm-model-invalid",
                    new LocalizedMessage("llm.problem.modelInvalid")).withFieldErrors(errors);
        }
        return ModelEntryRules.withDefaults(draft);
    }

    private LlmModel require(String modelKey) {
        return models.findById(modelKey).orElseThrow(() -> new LlmProblemException(HttpStatus.NOT_FOUND,
                "llm-model-not-found", new LocalizedMessage("llm.problem.modelNotFound", modelKey)));
    }
}
