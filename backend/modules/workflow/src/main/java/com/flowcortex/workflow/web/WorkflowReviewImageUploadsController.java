package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.WorkflowReviewService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code POST /api/v1/workflows/{id}/reviews/image-uploads} — a presigned PUT for one review image. Its own class
 * because {@link UploadRejectionHandler} renders a refused grant as 400 on {@code fileName}/{@code size}, which a
 * refused image on the review write must never be.
 */
@RestController
@RequestMapping("/api/v1/workflows/{id}/reviews/image-uploads")
class WorkflowReviewImageUploadsController {

    private final WorkflowReviewService reviews;
    private final CurrentUserResolver currentUserResolver;

    WorkflowReviewImageUploadsController(WorkflowReviewService reviews, CurrentUserResolver currentUserResolver) {
        this.reviews = reviews;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "createReviewImageUpload")
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<UploadsController.UploadResponse> createReviewImageUpload(@AuthenticationPrincipal Jwt jwt,
            @PathVariable UUID id, @Valid @RequestBody UploadsController.UploadRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.status(HttpStatus.CREATED).body(UploadsController.UploadResponse.from(
                reviews.imageGrant(callerId, id, request.fileName(), request.size())));
    }
}
