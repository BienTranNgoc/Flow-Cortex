package com.flowcortex.trash.web;

import com.flowcortex.trash.application.TrashListing;
import com.flowcortex.trash.domain.TrashKeys;
import com.flowcortex.trash.domain.TrashText;
import com.flowcortex.trash.web.dto.TrashItemView;
import com.flowcortex.trash.web.dto.TrashListView;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** {@code /api/v1/trash}: the caller's own Trash, read-only here; 503 while the Trash is switched off. */
@RestController
@RequestMapping("/api/v1/trash")
class TrashListController {

    private static final int MAX_QUERY_CODE_POINTS = 100;
    private static final int MAX_SIZE = 50;

    private final TrashCaller caller;
    private final TrashListing listing;
    private final MessageSource messages;

    TrashListController(TrashCaller caller, TrashListing listing, MessageSource messages) {
        this.caller = caller;
        this.listing = listing;
        this.messages = messages;
    }

    @Operation(operationId = "listTrash", summary = "List the caller's Trash with the badge counts")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (errors names type, q, sort, page or size)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "the Trash is switched off",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    TrashListView list(@AuthenticationPrincipal Jwt jwt,
            @Parameter(description = "Only entries of this type")
            @RequestParam(name = "type", required = false) String type,
            @Parameter(description = "Search text, at most 100 characters; matches names without case or diacritics")
            @RequestParam(name = "q", required = false) String q,
            @Parameter(schema = @Schema(allowableValues = {"recent", "expiring"}, defaultValue = "recent"))
            @RequestParam(name = "sort", required = false) String sort,
            @Parameter(schema = @Schema(minimum = "0", defaultValue = "0"))
            @RequestParam(name = "page", defaultValue = "0") int page,
            @Parameter(schema = @Schema(minimum = "1", maximum = "50", defaultValue = "20"))
            @RequestParam(name = "size", defaultValue = "20") int size,
            Locale locale) {
        UUID ownerId = caller.require(jwt);
        TrashListing.Query query = query(type, q, sort, page, size);
        TrashListing.Result result = listing.list(ownerId, query);
        List<TrashItemView> items = result.items().stream().map(entry -> view(entry, locale)).toList();
        return new TrashListView(items, query.page(), query.size(), result.total(), result.counts());
    }

    private static TrashListing.Query query(String type, String q, String sort, int page, int size) {
        if (type != null && !TrashKeys.validType(type)) {
            throw new TrashInvalidRequestException("type");
        }
        if (q != null && q.codePointCount(0, q.length()) > MAX_QUERY_CODE_POINTS) {
            throw new TrashInvalidRequestException("q");
        }
        TrashListing.Sort order = switch (sort == null ? "recent" : sort) {
            case "recent" -> TrashListing.Sort.RECENT;
            case "expiring" -> TrashListing.Sort.EXPIRING;
            default -> throw new TrashInvalidRequestException("sort");
        };
        if (page < 0) {
            throw new TrashInvalidRequestException("page");
        }
        if (size < 1 || size > MAX_SIZE) {
            throw new TrashInvalidRequestException("size");
        }
        return new TrashListing.Query(type, TrashText.key(q), order, page, size);
    }

    /** A code with no text in the reader's language, or in any, is shown as itself, so a missing key never fails the list. */
    private TrashItemView view(TrashListing.Entry entry, Locale locale) {
        String name = entry.name() != null ? entry.name() : text("trash.name.untitled", locale);
        return new TrashItemView(entry.type(), entry.itemId(), name, text(entry.locationCode(), locale),
                entry.deletedAt(), entry.expiresAt(), entry.remainingDays());
    }

    private String text(String code, Locale locale) {
        return messages.getMessage(code, null, code, locale);
    }
}
