package com.flowcortex.llm.web;

import com.flowcortex.llm.registry.ModelActivity;
import com.flowcortex.llm.registry.ProbeResult;
import com.flowcortex.llm.registry.ProviderOverview;
import com.flowcortex.llm.registry.ProviderRegistry;
import com.flowcortex.llm.registry.ProviderTestDecision;
import com.flowcortex.llm.registry.RegisteredProvider;
import com.flowcortex.llm.registry.RegisteredProvider.VendorModels;
import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.shared.security.authz.RequirePermission;
import com.flowcortex.shared.web.LocalizedMessage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/admin/ai/providers} — which providers the backend can call, what each lacks, the models a
 * provider offers, and a check that the provider answers. Setting values never leave the server.
 */
@RestController
@RequestMapping("/api/v1/admin/ai/providers")
class AdminAiProvidersController {

    private final ProviderRegistry registry;
    private final ModelActivity activity;

    AdminAiProvidersController(ProviderRegistry registry, ModelActivity activity) {
        this.registry = registry;
        this.activity = activity;
    }

    @Operation(operationId = "listAdminAiProviders")
    @RequirePermission(Permission.AI_PROVIDER_VIEW)
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ProviderOverview overview() {
        List<RegisteredProvider> providers = registry.list();
        return ProviderOverview.assemble(providers.stream().map(ProviderOverview::snapshot).toList(),
                activity.activeByModelKey(ProviderOverview.slotModelKeys(providers)));
    }

    @Operation(operationId = "listAdminAiProviderModels")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "ai-provider-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_VIEW)
    @GetMapping(value = "/{providerKey}/models", produces = MediaType.APPLICATION_JSON_VALUE)
    VendorModels models(@PathVariable String providerKey) {
        return registry.find(providerKey).orElseThrow(() -> notFound(providerKey)).listModels();
    }

    @Operation(operationId = "testAdminAiProvider")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "ai-provider-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @RequirePermission(Permission.AI_PROVIDER_UPDATE)
    @PostMapping(value = "/{providerKey}/test", produces = MediaType.APPLICATION_JSON_VALUE)
    ProbeResult test(@PathVariable String providerKey) {
        var provider = registry.find(providerKey);
        ProviderTestDecision decision = ProviderTestDecision.decide(provider);
        return switch (decision.kind()) {
            case NOT_FOUND -> throw notFound(providerKey);
            case MISSING_CONFIG -> ProbeResult.missingConfig(decision.missingSettings());
            case CONFIG_ONLY -> ProbeResult.notTested();
            case LIVE_CHECK -> provider.orElseThrow().liveCheck();
        };
    }

    private static LlmProblemException notFound(String providerKey) {
        return new LlmProblemException(HttpStatus.NOT_FOUND, "ai-provider-not-found",
                new LocalizedMessage("llm.problem.providerNotFound", providerKey));
    }
}
