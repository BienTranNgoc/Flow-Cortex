package com.flowcortex.notification.preferences;

import com.flowcortex.notification.preferences.PreferencesService.Preferences;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.UserDirectory;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.context.MessageSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/notifications/preferences}: the caller's own choices. The PATCH body is checked against
 * an allow-list of three boolean fields and either applies completely or writes nothing.
 */
@RestController
@RequestMapping("/api/v1/notifications/preferences")
class PreferencesController {

    private final PreferencesService service;
    private final UserDirectory userDirectory;
    private final MessageSource messages;

    PreferencesController(PreferencesService service, UserDirectory userDirectory, MessageSource messages) {
        this.service = service;
        this.userDirectory = userDirectory;
        this.messages = messages;
    }

    @Operation(operationId = "getNotificationPreferences")
    @GetMapping(produces = MediaType.APPLICATION_JSON_VALUE)
    PreferencesView get(@AuthenticationPrincipal Jwt jwt) {
        return view(service.get(currentUserId(jwt)));
    }

    @Operation(operationId = "updateNotificationPreferences",
            requestBody = @io.swagger.v3.oas.annotations.parameters.RequestBody(content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE, schema = @Schema(implementation = PatchBody.class))))
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "empty body, or a field that is not one of the three booleans",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PatchMapping(consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    PreferencesView update(@AuthenticationPrincipal Jwt jwt, @RequestBody(required = false) Map<String, Object> body,
            Locale locale) {
        UUID userId = currentUserId(jwt);
        PreferencePatch.Parsed parsed = PreferencePatch.parse(body);
        if (parsed.errorField() != null) {
            throw refused(parsed.errorField(), locale);
        }
        return view(service.update(userId, parsed.patch()));
    }

    private LocalizedStatusException refused(String field, Locale locale) {
        boolean empty = PreferencePatch.BODY.equals(field);
        String text = empty
                ? messages.getMessage("notification.preferences.empty", null, locale)
                : messages.getMessage("notification.preferences.invalid", new Object[] {field}, locale);
        LocalizedStatusException exception = new LocalizedStatusException(HttpStatus.BAD_REQUEST,
                new LocalizedMessage("problem.validationFailed"));
        exception.getBody().setProperty("errors", Map.of(field, text));
        return exception;
    }

    private UUID currentUserId(Jwt jwt) {
        UUID id = UUID.fromString(jwt.getSubject());
        return userDirectory.findById(id)
                .orElseThrow(() -> new NotFoundException(new LocalizedMessage("user.current.notFound", id)))
                .id();
    }

    private static PreferencesView view(Preferences preferences) {
        return new PreferencesView(preferences.inApp(), preferences.careEmail(), preferences.marketingEmail());
    }

    record PreferencesView(boolean inApp, boolean careEmail, boolean marketingEmail) {
    }

    /** Documents the PATCH body for the contract; the handler reads a map and never binds this type. */
    record PatchBody(Boolean inApp, Boolean careEmail, Boolean marketingEmail) {
    }
}
