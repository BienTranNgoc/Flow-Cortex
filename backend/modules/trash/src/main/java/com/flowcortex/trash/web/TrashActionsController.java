package com.flowcortex.trash.web;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.trash.application.TrashItemActions;
import com.flowcortex.trash.application.TrashItemActions.EmptyResult;
import com.flowcortex.trash.application.TrashItemActions.Result;
import com.flowcortex.trash.application.TrashItemActions.Status;
import com.flowcortex.trash.domain.EmptyCursor;
import com.flowcortex.trash.domain.TrashRequestRules;
import com.flowcortex.trash.domain.TrashRequestRules.ItemRef;
import com.flowcortex.trash.web.dto.TrashBulkView;
import com.flowcortex.trash.web.dto.TrashEmptyRequest;
import com.flowcortex.trash.web.dto.TrashEmptyView;
import com.flowcortex.trash.web.dto.TrashItemsRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/trash} writes: restore, purge and empty on the caller's own entries; 503 while the Trash is
 * switched off. One item answers with the status of its outcome; several answer 200 with what happened to each.
 */
@RestController
@RequestMapping("/api/v1/trash")
class TrashActionsController {

    private final TrashCaller caller;
    private final TrashItemActions actions;
    private final MessageSource messages;

    TrashActionsController(TrashCaller caller, TrashItemActions actions, MessageSource messages) {
        this.caller = caller;
        this.actions = actions;
        this.messages = messages;
    }

    @Operation(operationId = "restoreTrashItems", summary = "Restore items from the Trash")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.items)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not-found (a single item that is not in the caller's Trash)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "conflict (a single item the owning module cannot restore now)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "the Trash is switched off",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/restore", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    TrashBulkView restore(@AuthenticationPrincipal Jwt jwt, @RequestBody TrashItemsRequest body, Locale locale) {
        UUID ownerId = caller.require(jwt);
        List<ItemRef> items = checkedItems(body);
        return answer(items, actions.restoreMany(ownerId, items), locale);
    }

    @Operation(operationId = "purgeTrashItems", summary = "Delete items from the Trash for good")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.items)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "not-found (a single item that is not in the caller's Trash)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "409", description = "conflict (a single item the owning module cannot delete now)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "the Trash is switched off",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/purge", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    TrashBulkView purge(@AuthenticationPrincipal Jwt jwt, @RequestBody TrashItemsRequest body, Locale locale) {
        UUID ownerId = caller.require(jwt);
        List<ItemRef> items = checkedItems(body);
        return answer(items, actions.purgeMany(ownerId, items), locale);
    }

    @Operation(operationId = "emptyTrash", summary = "Delete the caller's Trash for good, at most 500 entries a call")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors.next)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "the Trash is switched off",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/empty", produces = MediaType.APPLICATION_JSON_VALUE)
    TrashEmptyView empty(@AuthenticationPrincipal Jwt jwt,
            @RequestBody(required = false) TrashEmptyRequest body) {
        UUID ownerId = caller.require(jwt);
        Optional<EmptyCursor> after = cursor(body);
        EmptyResult result = actions.empty(ownerId, after);
        return new TrashEmptyView(result.purged(), result.blocked(), result.failed(), result.remaining(),
                result.next().map(EmptyCursor::encode).orElse(null));
    }

    private static List<ItemRef> checkedItems(TrashItemsRequest body) {
        List<ItemRef> items = body.items() == null ? null : body.items().stream()
                .map(item -> item == null ? null : new ItemRef(item.type(), item.id())).toList();
        TrashRequestRules.checkItems(items).ifPresent(field -> {
            throw new TrashInvalidRequestException(field);
        });
        return items;
    }

    private static Optional<EmptyCursor> cursor(TrashEmptyRequest body) {
        if (body == null || body.next() == null) {
            return Optional.empty();
        }
        return Optional.of(EmptyCursor.decode(body.next()).orElseThrow(() -> new TrashInvalidRequestException("next")));
    }

    /** One item answers with the status of what happened to it; a list answers 200 with each item's outcome. */
    private TrashBulkView answer(List<ItemRef> items, List<Result> results, Locale locale) {
        if (items.size() == 1) {
            RuntimeException rejection = rejection(results.getFirst());
            if (rejection != null) {
                throw rejection;
            }
        }
        List<TrashItemsRequest.Item> succeeded = new ArrayList<>();
        List<TrashBulkView.Skipped> skipped = new ArrayList<>();
        for (int position = 0; position < items.size(); position++) {
            ItemRef item = items.get(position);
            Result result = results.get(position);
            if (result.status() == Status.SUCCEEDED) {
                succeeded.add(new TrashItemsRequest.Item(item.type(), item.id()));
            } else {
                skipped.add(skippedItem(item, result, locale));
            }
        }
        return new TrashBulkView(succeeded, skipped);
    }

    /** The failure that answers a lone item that did not succeed, null when it did. */
    private static RuntimeException rejection(Result result) {
        return switch (result.status()) {
            case SUCCEEDED -> null;
            case NOT_FOUND -> new NotFoundException(new LocalizedMessage("trash.item.notFound"));
            case CONFLICT, BLOCKED -> new TrashConflictException(result.messageKey());
            case FAILED -> result.failure();
        };
    }

    /** A message code with no text in the reader's language, or in any, is shown as itself. */
    private TrashBulkView.Skipped skippedItem(ItemRef item, Result result, Locale locale) {
        String reason = switch (result.status()) {
            case NOT_FOUND -> "notFound";
            case CONFLICT -> "conflict";
            case BLOCKED -> "blocked";
            case FAILED -> "failed";
            case SUCCEEDED -> throw new IllegalArgumentException("a succeeded item is not skipped");
        };
        String key = result.messageKey();
        String message = key == null ? null : messages.getMessage(key, null, key, locale);
        return new TrashBulkView.Skipped(item.type(), item.id(), reason, message);
    }
}
