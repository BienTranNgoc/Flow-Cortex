package com.flowcortex.workflow.web;

import com.flowcortex.workflow.application.RatingSummary;
import com.flowcortex.workflow.application.ReviewViews.HelpfulResult;
import com.flowcortex.workflow.application.ReviewViews.ReviewImageView;
import com.flowcortex.workflow.application.ReviewViews.ReviewListItemView;
import com.flowcortex.workflow.application.ReviewViews.ReviewPage;
import com.flowcortex.workflow.application.ReviewViews.ReviewView;
import com.flowcortex.workflow.application.WorkflowReviewService;
import com.flowcortex.workflow.domain.ReviewSort;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows/{id}/reviews} — the caller's single review ({@code mine}), the review list and summary,
 * helpful marks and reports. Image grants are a separate controller so a refused write upload is never rendered as a
 * refused grant.
 */
@RestController
@RequestMapping("/api/v1/workflows/{id}/reviews")
class WorkflowReviewsController {

    private static final int MAX_PAGE_SIZE = 50;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final WorkflowReviewService reviews;
    private final CurrentUserResolver currentUserResolver;

    WorkflowReviewsController(WorkflowReviewService reviews, CurrentUserResolver currentUserResolver) {
        this.reviews = reviews;
        this.currentUserResolver = currentUserResolver;
    }

    @Operation(operationId = "putMyWorkflowReview")
    @PutMapping(value = "/mine", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReviewResponse> putMyReview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @RequestBody ReviewRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ReviewResponse.from(
                reviews.write(callerId, id, request.rating(), request.body(), request.images())));
    }

    @Operation(operationId = "getMyWorkflowReview")
    @GetMapping(value = "/mine", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReviewResponse> getMyReview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ReviewResponse.from(reviews.mine(callerId, id)));
    }

    @Operation(operationId = "deleteMyWorkflowReview")
    @DeleteMapping("/mine")
    ResponseEntity<Void> deleteMyReview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        reviews.deleteMine(callerId, id);
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "listWorkflowReviews")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReviewPageResponse> listReviews(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Parameter(schema = @Schema(allowableValues = {"newest", "helpful"}, defaultValue = "newest"))
            @RequestParam(defaultValue = "newest") @Pattern(regexp = "newest|helpful") String sort,
            @RequestParam(required = false) @Min(1) @Max(5) Integer rating,
            @RequestParam(defaultValue = "false") boolean withComment,
            @RequestParam(defaultValue = "false") boolean withImages,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID callerId = currentUserResolver.require(jwt);
        ReviewSort order = "helpful".equals(sort) ? ReviewSort.HELPFUL : ReviewSort.NEWEST;
        return ResponseEntity.ok(ReviewPageResponse.from(
                reviews.page(callerId, id, order, rating, withComment, withImages, page, size)));
    }

    @Operation(operationId = "getWorkflowReviewSummary")
    @GetMapping(value = "/summary", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReviewSummaryResponse> getReviewSummary(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(ReviewSummaryResponse.from(reviews.summary(callerId, id)));
    }

    @Operation(operationId = "setWorkflowReviewHelpful")
    @PutMapping(value = "/{reviewId}/helpful", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<HelpfulResponse> setReviewHelpful(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID reviewId, @Valid @RequestBody HelpfulRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        HelpfulResult result = reviews.setHelpful(callerId, id, reviewId, request.helpful());
        return ResponseEntity.ok(new HelpfulResponse(result.helpful(), result.helpfulCount()));
    }

    @Operation(operationId = "reportWorkflowReview")
    @PostMapping(value = "/{reviewId}/report", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<ReportedResponse> reportReview(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @PathVariable UUID reviewId, @RequestBody ReportRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        reviews.report(callerId, id, reviewId, request.reason(), request.details());
        return ResponseEntity.ok(new ReportedResponse(true));
    }

    /**
     * {@code rating} is a {@code Number} so Jackson cannot round 4.5 to 4; the rule that it is an integer from 1 to 5
     * is the service's. {@code images} absent keeps the current images, {@code []} removes them.
     */
    record ReviewRequest(
            @Schema(type = "integer", minimum = "1", maximum = "5") Number rating,
            @Schema(maxLength = 5000, nullable = true) String body,
            @Schema(nullable = true) List<UUID> images) {
    }

    record HelpfulRequest(@NotNull Boolean helpful) {
    }

    record ReportRequest(
            @Schema(allowableValues = {"spam", "scam", "harmful", "harassment", "misinformation", "copyright", "nsfw",
                    "other"}) String reason,
            @Schema(maxLength = 1000, nullable = true) String details) {
    }

    record ReviewImageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) String url,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant expiresAt) {

        static ReviewImageResponse from(ReviewImageView view) {
            return new ReviewImageResponse(view.id(), view.url(), view.expiresAt());
        }
    }

    record ReviewResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) int rating,
            @Schema(nullable = true) String body,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ReviewImageResponse> images,
            @Schema(requiredMode = RequiredMode.REQUIRED) int helpfulCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt) {

        static ReviewResponse from(ReviewView view) {
            return new ReviewResponse(view.id(), view.rating(), view.body(),
                    view.images().stream().map(ReviewImageResponse::from).toList(), view.helpfulCount(),
                    view.createdAt(), view.updatedAt());
        }
    }

    /** A review in the list; {@code authorName} is null when the author's account is gone. */
    record ReviewListItemResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) UUID id,
            @Schema(requiredMode = RequiredMode.REQUIRED) int rating,
            @Schema(nullable = true) String body,
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ReviewImageResponse> images,
            @Schema(requiredMode = RequiredMode.REQUIRED) int helpfulCount,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant createdAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) Instant updatedAt,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean helpfulByMe,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean mine,
            @Schema(nullable = true) String authorName) {

        static ReviewListItemResponse from(ReviewListItemView view) {
            return new ReviewListItemResponse(view.id(), view.rating(), view.body(),
                    view.images().stream().map(ReviewImageResponse::from).toList(), view.helpfulCount(),
                    view.createdAt(), view.updatedAt(), view.helpfulByMe(), view.mine(), view.authorName());
        }
    }

    record ReviewPageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<ReviewListItemResponse> data,
            @Schema(requiredMode = RequiredMode.REQUIRED) Meta meta) {

        record Meta(
                @Schema(requiredMode = RequiredMode.REQUIRED) long total,
                @Schema(requiredMode = RequiredMode.REQUIRED) int page,
                @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
        }

        static ReviewPageResponse from(ReviewPage page) {
            return new ReviewPageResponse(page.data().stream().map(ReviewListItemResponse::from).toList(),
                    new Meta(page.total(), page.page(), page.size()));
        }
    }

    /** {@code average} is null while there is no review; {@code distribution} is keyed {@code "1"} to {@code "5"}. */
    record ReviewSummaryResponse(
            @Schema(nullable = true) BigDecimal average,
            @Schema(requiredMode = RequiredMode.REQUIRED) long total,
            @Schema(requiredMode = RequiredMode.REQUIRED) Map<String, Long> distribution) {

        static ReviewSummaryResponse from(RatingSummary summary) {
            return new ReviewSummaryResponse(summary.average(), summary.total(), summary.distribution());
        }
    }

    record HelpfulResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean helpful,
            @Schema(requiredMode = RequiredMode.REQUIRED) int helpfulCount) {
    }

    record ReportedResponse(@Schema(requiredMode = RequiredMode.REQUIRED) boolean reported) {
    }
}
