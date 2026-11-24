package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.ProductVideoPresetCatalog;
import com.flowcortex.workflow.application.ProductVideoService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-videos")
class ProductVideosController {

    private final ProductVideoService productVideos;
    private final ProductVideoPresetCatalog presetCatalog;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    ProductVideosController(ProductVideoService productVideos, ProductVideoPresetCatalog presetCatalog,
            CurrentUserResolver currentUserResolver, CreditAccounts creditAccounts) {
        this.productVideos = productVideos;
        this.presetCatalog = presetCatalog;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    record TemplatesResponse(
            List<ProductVideoPresetCatalog.TemplateSpec> templates,
            List<ProductVideoPresetCatalog.McPreset> mcPresets,
            List<ProductVideoPresetCatalog.BackgroundPreset> backgroundPresets) {
    }

    @GetMapping(value = "/templates", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<TemplatesResponse> listTemplates() {
        return ResponseEntity.ok(new TemplatesResponse(
                presetCatalog.templates(creditAccounts),
                presetCatalog.mcPresets(),
                presetCatalog.backgroundPresets()));
    }

    @GetMapping(value = "/presets/{category}/{id}", produces = MediaType.IMAGE_JPEG_VALUE)
    ResponseEntity<byte[]> getPresetImage(@PathVariable String category, @PathVariable String id) {
        byte[] bytes = presetCatalog.loadPresetImage(category, id);
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(1, TimeUnit.DAYS).cachePublic().immutable())
                .contentType(MediaType.IMAGE_JPEG)
                .body(bytes);
    }

    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> startProductVideoRun(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProductVideoRunRequest request) {
        UUID ownerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(productVideos.start(ownerId, request.draft(), request.uploadIds(),
                request.mcUploadId(), request.backgroundUploadId(),
                Boolean.TRUE.equals(request.acknowledgeCost()), null), creditAccounts));
    }

    @PostMapping(value = "/runs/{runId}/keyframes/regenerate", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> regenerateKeyframe(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID runId,
            @Valid @RequestBody(required = false) RegenerateKeyframeRequest request) {
        UUID ownerId = currentUserResolver.require(jwt);
        String backgroundPresetId = request != null ? request.backgroundPresetId() : null;
        String sceneNote = request != null ? request.sceneNote() : null;
        boolean acknowledgeCost = request != null && Boolean.TRUE.equals(request.acknowledgeCost());
        return ResponseEntity.ok(RunResponse.from(productVideos.regenerateKeyframe(
                runId, ownerId, backgroundPresetId, sceneNote, acknowledgeCost), creditAccounts));
    }
}
