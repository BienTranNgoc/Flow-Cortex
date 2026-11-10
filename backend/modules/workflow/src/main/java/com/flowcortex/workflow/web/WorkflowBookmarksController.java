package com.flowcortex.workflow.web;

import com.flowcortex.credit.api.CreditAccounts;
import com.flowcortex.workflow.application.WorkflowBookmarkService;
import com.flowcortex.workflow.application.WorkflowBookmarkService.BookmarkPage;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/workflows} bookmark routes: save or unsave a workflow the caller can see, read that flag, and page the
 * caller's saved workflows. {@code /bookmarked} is a literal segment, so it wins over {@code /{id}}.
 */
@RestController
@RequestMapping("/api/v1/workflows")
class WorkflowBookmarksController {

    private static final int MAX_PAGE_SIZE = 50;
    /** Keeps {@code page * size} within an {@code int}, the offset type Spring Data pages with. */
    private static final int MAX_PAGE = Integer.MAX_VALUE / MAX_PAGE_SIZE;

    private final WorkflowBookmarkService bookmarks;
    private final CurrentUserResolver currentUserResolver;
    private final CreditAccounts creditAccounts;

    WorkflowBookmarksController(WorkflowBookmarkService bookmarks, CurrentUserResolver currentUserResolver,
            CreditAccounts creditAccounts) {
        this.bookmarks = bookmarks;
        this.currentUserResolver = currentUserResolver;
        this.creditAccounts = creditAccounts;
    }

    @Operation(operationId = "setWorkflowBookmark")
    @PutMapping(value = "/{id}/bookmark", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BookmarkResponse> setBookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id,
            @Valid @RequestBody BookmarkRequest request) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new BookmarkResponse(bookmarks.set(callerId, id, request.bookmarked())));
    }

    @Operation(operationId = "getWorkflowBookmark")
    @GetMapping(value = "/{id}/bookmark", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BookmarkResponse> getBookmark(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID id) {
        UUID callerId = currentUserResolver.require(jwt);
        return ResponseEntity.ok(new BookmarkResponse(bookmarks.get(callerId, id)));
    }

    @Operation(operationId = "listBookmarkedWorkflows")
    @GetMapping(value = "/bookmarked", produces = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<BookmarkedWorkflowPageResponse> listBookmarked(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(defaultValue = "0") @Min(0) @Max(MAX_PAGE) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(MAX_PAGE_SIZE) int size) {
        UUID callerId = currentUserResolver.require(jwt);
        BookmarkPage found = bookmarks.page(callerId, page, size);
        return ResponseEntity.ok(BookmarkedWorkflowPageResponse.from(found, page, size, creditAccounts));
    }

    record BookmarkRequest(@NotNull Boolean bookmarked) {
    }

    record BookmarkResponse(@Schema(requiredMode = RequiredMode.REQUIRED) boolean bookmarked) {
    }

    /** One page of the caller's saved workflows, newest saved first; {@code meta.total} counts only visible ones. */
    record BookmarkedWorkflowPageResponse(
            @Schema(requiredMode = RequiredMode.REQUIRED) List<Item> data,
            @Schema(requiredMode = RequiredMode.REQUIRED) Meta meta) {

        record Item(
                @Schema(requiredMode = RequiredMode.REQUIRED) WorkflowResponse workflow,
                @Schema(requiredMode = RequiredMode.REQUIRED) Instant bookmarkedAt) {
        }

        record Meta(
                @Schema(requiredMode = RequiredMode.REQUIRED) long total,
                @Schema(requiredMode = RequiredMode.REQUIRED) int page,
                @Schema(requiredMode = RequiredMode.REQUIRED) int size) {
        }

        static BookmarkedWorkflowPageResponse from(BookmarkPage found, int page, int size, CreditAccounts credits) {
            return new BookmarkedWorkflowPageResponse(
                    found.data().stream()
                            .map(item -> new Item(WorkflowResponse.from(item.workflow(), credits), item.bookmarkedAt()))
                            .toList(),
                    new Meta(found.total(), page, size));
        }
    }
}
