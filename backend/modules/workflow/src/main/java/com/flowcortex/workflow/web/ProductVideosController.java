package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.ProductVideoService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/product-videos")
class ProductVideosController {

    private final ProductVideoService productVideos;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    ProductVideosController(ProductVideoService productVideos, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.productVideos = productVideos;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @PostMapping(value = "/runs", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<RunResponse> startProductVideoRun(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ProductVideoRunRequest request) {
        UUID ownerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(RunResponse.from(productVideos.start(ownerId, request.draft(), request.uploadIds(),
                Boolean.TRUE.equals(request.acknowledgeCost())), creditAccounts));
    }
}
