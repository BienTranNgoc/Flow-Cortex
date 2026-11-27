package com.flowcortex.llm.web;

import com.flowcortex.llm.api.ModelDescriptor;
import com.flowcortex.llm.catalog.ModelCatalogService;
import com.flowcortex.llm.catalog.ModelCatalogService.CapabilityDraft;
import com.flowcortex.llm.catalog.ModelCatalogService.CapabilityList;
import com.flowcortex.llm.catalog.ModelCatalogService.CapabilityView;
import com.flowcortex.llm.catalog.ModelCatalogService.ModelView;
import com.flowcortex.llm.catalog.ModelRowRules.ModelRowDraft;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.PageView;
import com.flowcortex.shared.web.audit.AdminAudit;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;
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

/**
 * {@code /api/v1/admin/ai} — the capability catalog, the model rows under each capability and the model
 * descriptors. A model row is addressed by its capability and the {@code key} query parameter because a vendor's
 * model id may hold {@code /}.
 */
@RestController
@RequestMapping("/api/v1/admin/ai")
class AdminAiCatalogController {

    private static final int MAX_PAGE = 100_000;

    private static final String CATEGORY = "^(ingest|analyze|generate|transform|present|review|deliver|control)$";
    private static final String GROUP = "^(input|generate|edit|replace|analyze|deliver|control)$";
    private static final String ICON = "^[a-z0-9-]{1,64}$";
    private static final String MODE = "^(text|image|video|audio|transform|deliver|ingest|control)$";
    private static final String MODEL_KEY = "^[A-Za-z0-9._:-]+(/[A-Za-z0-9._:-]+)*$";
    private static final String PROVIDER_KEY = "^[a-z0-9._-]{1,64}$";

    private final ModelCatalogService catalog;

    AdminAiCatalogController(ModelCatalogService catalog) {
        this.catalog = catalog;
    }

    @Schema(name = "CatalogCapabilityRequest")
    record CapabilityRequest(
            @NotBlank @Pattern(regexp = CATEGORY) String category,
            @NotBlank @Pattern(regexp = GROUP) String uiGroup,
            @Pattern(regexp = ICON) String uiIcon) {

        CapabilityDraft draft() {
            return new CapabilityDraft(category, uiGroup, uiIcon);
        }
    }

    @Schema(name = "CatalogModelCreateRequest")
    record ModelCreateRequest(
            @NotBlank @Size(max = 128) @Pattern(regexp = MODEL_KEY) String modelKey,
            @NotBlank @Pattern(regexp = PROVIDER_KEY) String providerKey,
            @Size(max = 128) String vendorLabel,
            @Size(max = 128) String makerLabel,
            @NotBlank @Pattern(regexp = MODE) String mode,
            Boolean enabled,
            Boolean notImplemented) {

        ModelRowDraft draft() {
            return new ModelRowDraft(modelKey, providerKey, label(vendorLabel), label(makerLabel), mode,
                    enabled == null || enabled, Boolean.TRUE.equals(notImplemented));
        }
    }

    @Schema(name = "CatalogModelReplaceRequest")
    record ModelReplaceRequest(
            @NotBlank @Pattern(regexp = PROVIDER_KEY) String providerKey,
            @Size(max = 128) String vendorLabel,
            @Size(max = 128) String makerLabel,
            @NotBlank @Pattern(regexp = MODE) String mode,
            Boolean enabled,
            Boolean notImplemented) {

        ModelRowDraft draft(String modelKey) {
            return new ModelRowDraft(modelKey, providerKey, label(vendorLabel), label(makerLabel), mode,
                    enabled == null || enabled, Boolean.TRUE.equals(notImplemented));
        }
    }

    /** A label that is blank after trimming is no label, never an empty string the database refuses. */
    private static String label(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.strip();
    }

    @Operation(operationId = "listAdminAiCapabilities")
    @ApiResponse(responseCode = "200")
    @RequirePermission(Permission.AI_MODEL_VIEW)
    @GetMapping(value = "/capabilities", produces = MediaType.APPLICATION_JSON_VALUE)
    CapabilityList listCapabilities() {
        return catalog.capabilities();
    }

    @Operation(operationId = "getAdminAiCapability")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "capability-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_MODEL_VIEW)
    @GetMapping(value = "/capabilities/{capability}", produces = MediaType.APPLICATION_JSON_VALUE)
    CapabilityView getCapability(@PathVariable String capability) {
        return catalog.capability(capability);
    }

    @Operation(operationId = "replaceAdminAiCapability")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "capability-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @AdminAudit(value = "capability_catalog.replace", resourceParam = "capability",
            capture = {"category", "uiGroup", "uiIcon"})
    @RequirePermission(Permission.AI_MODEL_UPDATE)
    @PutMapping(value = "/capabilities/{capability}", produces = MediaType.APPLICATION_JSON_VALUE)
    CapabilityView replaceCapability(@PathVariable String capability, @Valid @RequestBody CapabilityRequest body) {
        return catalog.replaceCapability(capability, body.draft());
    }

    @Operation(operationId = "listAdminAiCatalogModels")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "capability-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_MODEL_VIEW)
    @GetMapping(value = "/capabilities/{capability}/models", produces = MediaType.APPLICATION_JSON_VALUE)
    PageView<ModelView> listModels(@PathVariable String capability,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "50") @Min(1) @Max(ModelCatalogService.MAX_PAGE_SIZE) int size) {
        return catalog.models(capability, page, size);
    }

    // @ResponseStatus documents 201/204 for springdoc/orval only; the ResponseEntity sets the same status.
    @Operation(operationId = "createAdminAiCatalogModel")
    @ResponseStatus(HttpStatus.CREATED)
    @ApiResponse(responseCode = "201")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), catalog-model-invalid (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "capability-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "catalog-model-exists",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @AdminAudit(value = "model_catalog.create", resourceParam = "capability",
            capture = {"modelKey", "providerKey", "mode", "enabled"})
    @RequirePermission(Permission.AI_MODEL_UPDATE)
    @PostMapping(value = "/capabilities/{capability}/models", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ModelView> createModel(@PathVariable String capability,
            @Valid @RequestBody ModelCreateRequest body) {
        return ResponseEntity.status(HttpStatus.CREATED).body(catalog.createModel(capability, body.draft()));
    }

    @Operation(operationId = "getAdminAiCatalogModel")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "capability-not-found, catalog-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_MODEL_VIEW)
    @GetMapping(value = "/capabilities/{capability}/model", produces = MediaType.APPLICATION_JSON_VALUE)
    ModelView getModel(@PathVariable String capability,
            @RequestParam("key") @NotBlank @Size(max = 128) @Pattern(regexp = MODEL_KEY) String key) {
        return catalog.model(capability, key);
    }

    @Operation(operationId = "replaceAdminAiCatalogModel")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors), catalog-model-invalid (errors)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "capability-not-found, catalog-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @AdminAudit(value = "model_catalog.replace", resourceParam = "capability",
            capture = {"providerKey", "mode", "enabled"})
    @RequirePermission(Permission.AI_MODEL_UPDATE)
    @PutMapping(value = "/capabilities/{capability}/model", produces = MediaType.APPLICATION_JSON_VALUE)
    ModelView replaceModel(@PathVariable String capability,
            @RequestParam("key") @NotBlank @Size(max = 128) @Pattern(regexp = MODEL_KEY) String key,
            @Valid @RequestBody ModelReplaceRequest body) {
        return catalog.replaceModel(capability, key, body.draft(key));
    }

    @Operation(operationId = "deleteAdminAiCatalogModel")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @ApiResponse(responseCode = "204")
    @ApiResponse(responseCode = "404", description = "capability-not-found, catalog-model-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @AdminAudit(value = "model_catalog.delete", resourceParam = "capability")
    @RequirePermission(Permission.AI_MODEL_UPDATE)
    @DeleteMapping("/capabilities/{capability}/model")
    ResponseEntity<Void> deleteModel(@PathVariable String capability,
            @RequestParam("key") @NotBlank @Size(max = 128) @Pattern(regexp = MODEL_KEY) String key) {
        catalog.deleteModel(capability, key);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listAdminAiModelDescriptors")
    @ApiResponse(responseCode = "200")
    @RequirePermission(Permission.AI_MODEL_VIEW)
    @GetMapping(value = "/model-descriptors", produces = MediaType.APPLICATION_JSON_VALUE)
    List<ModelDescriptor> listDescriptors() {
        return catalog.descriptors();
    }
}
