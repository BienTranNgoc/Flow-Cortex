package com.flowcortex.notification.web;

import com.flowcortex.notification.application.NotificationService;
import com.flowcortex.notification.application.NotificationService.Item;
import com.flowcortex.notification.application.NotificationService.Listing;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/notifications}: the caller's own inbox. An id that is not a UUID, is unknown or
 * belongs to another member is the same 404, so ids cannot be probed.
 */
@RestController
@RequestMapping("/api/v1/notifications")
class NotificationsController {

    private final NotificationService service;
    private final UserDirectory userDirectory;

    NotificationsController(NotificationService service, UserDirectory userDirectory) {
        this.service = service;
        this.userDirectory = userDirectory;
    }

    @Operation(operationId = "listNotifications")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "filter, page or size out of range",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    NotificationPage list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(name = "filter", required = false) String filter,
            @RequestParam(name = "page", required = false) String page,
            @RequestParam(name = "size", required = false) String size, Locale locale) {
        Listing listing = service.list(currentUserId(jwt), filter, page, size, locale);
        return new NotificationPage(listing.items().stream().map(NotificationsController::item).toList(),
                listing.page(), listing.size(), listing.hasMore(), listing.unreadCount());
    }

    @Operation(operationId = "getUnreadNotificationCount")
    @GetMapping(path = "/unread-count", produces = MediaType.APPLICATION_JSON_VALUE)
    UnreadCount unreadCount(@AuthenticationPrincipal Jwt jwt) {
        return new UnreadCount(service.unreadCount(currentUserId(jwt)));
    }

    @Operation(operationId = "markNotificationRead")
    @ApiResponse(responseCode = "204", description = "marked read, also when it already was")
    @ApiResponse(responseCode = "404", description = "unknown id, or not the caller's",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping("/{id}/read")
    ResponseEntity<Void> markRead(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") String id) {
        UUID userId = currentUserId(jwt);
        if (!service.markRead(userId, parse(id))) {
            throw notFound();
        }
        return ResponseEntity.noContent().build();
    }

    @Operation(operationId = "markAllNotificationsRead")
    @PostMapping(path = "/read-all", produces = MediaType.APPLICATION_JSON_VALUE)
    ReadAllResult markAllRead(@AuthenticationPrincipal Jwt jwt) {
        return new ReadAllResult(service.markAllRead(currentUserId(jwt)));
    }

    private UUID currentUserId(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }

    private static UUID parse(String id) {
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException(new LocalizedMessage("notification.notFound"));
    }

    private static NotificationItem item(Item item) {
        return new NotificationItem(item.id(), item.type(), item.title(), item.body(), item.link(), item.read(),
                item.createdAt());
    }

    record NotificationItem(UUID id, String type, String title, String body, String link, boolean read,
            Instant createdAt) {
    }

    record NotificationPage(List<NotificationItem> items, int page, int size, boolean hasMore, long unreadCount) {
    }

    record UnreadCount(long unreadCount) {
    }

    record ReadAllResult(int marked) {
    }
}
