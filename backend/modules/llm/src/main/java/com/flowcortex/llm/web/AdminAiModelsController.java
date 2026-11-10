package com.flowcortex.llm.web;

import com.flowcortex.llm.registry.LlmModelService;
import com.flowcortex.llm.registry.LlmModelService.AiModel;
import com.flowcortex.llm.registry.ModelEntryRules.ModelDraft;
import com.flowcortex.llm.registry.ProbeResult;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/admin/ai/models} — the registry of language models the console manages. */
@RestController
@RequestMapping("/api/v1/admin/ai/models")
class AdminAiModelsController {

    private static final int MAX_PAGE = 100_000;

    private final LlmModelService models;

    AdminAiModelsController(LlmModelService models) {
        this.models = models;
    }

    @Schema(name = "AiModelRequest")
    record ModelRequest(
            @NotBlank @Size(max = 128) String modelKey,
            @NotBlank @Size(max = 32) String providerKey,
            @NotBlank @Size(max = 128) String displayName,
            @Min(1) @Max(8192) Integer maxOutputTokens,
            Boolean supportsVision,
            Boolean supportsAudio,
            Boolean active,
            @Min(0) @Max(32767) Integer displayOrder) {

        ModelDraft draft() {
            return new ModelDraft(modelKey.strip(), providerKey.strip(), displayName.strip(), maxOutputTokens,
                    supportsVision, supportsAudio, active, displayOrder == null ? null : displayOrder.shortValue());
        }
    }

    @Operation(operationId = "listAdminAiModels")
    @ApiResponse(responseCode = "200")
    @RequirePermission(Permission.AI_PROVIDER_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<AiModel> list(
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(LlmModelService.MAX_PAGE_SIZE) int size) {
        return models.page(page, size);
    }

    @Operation(operationId = "getAdminAiModel")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "llm-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_VIEW)
    @GetMapping(value = "/{modelKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    AiModel get(@PathVariable String modelKey) {
        return models.get(modelKey);
    }

    // @ResponseStatus documents 201/204 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "createAdminAiModel")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), llm-model-invalid (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "llm-model-exists",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_UPDATE)
    @PostMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<AiModel> create(@Valid @RequestBody ModelRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(models.create(body.draft()));
    }

    @Operation(operationId = "replaceAdminAiModel")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), llm-model-invalid (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "llm-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_UPDATE)
    @PutMapping(value = "/{modelKey}", produces = MediaType.APPLICATION_JSON_VALUE)
    AiModel replace(@PathVariable String modelKey, @Valid @RequestBody ModelRequest body) {
        return models.replace(modelKey, body.draft());
    }

    @Operation(operationId = "deleteAdminAiModel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "llm-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_UPDATE)
    @DeleteMapping("/{modelKey}")
    ResponseEntity<Void> delete(@PathVariable String modelKey) {
        models.delete(modelKey);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "testAdminAiModel")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "llm-model-not-found, ai-provider-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_UPDATE)
    @PostMapping(value = "/{modelKey}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    ProbeResult test(@PathVariable String modelKey) {
        return models.test(modelKey);
    }
}
