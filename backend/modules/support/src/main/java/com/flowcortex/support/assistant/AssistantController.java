package com.flowcortex.support.assistant;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.support.assistant.AssistantService.AssistantReply;
import com.flowcortex.support.assistant.AssistantService.ConversationView;
import com.flowcortex.support.web.SupportCaller;
import com.flowcortex.support.web.SupportProblemException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code /api/v1/support/assistant} — questions and the latest conversation, for a member (token) or
 * a guest (the {@code X-Support-Session} header, a UUID v4 the browser keeps). A guest's session id
 * and network address are kept only as keyed hashes ({@link GuestKeys}).
 */
@RestController
@RequestMapping("/api/v1/support/assistant")
class AssistantController {

    static final String SESSION_HEADER = "X-Support-Session";

    private final AssistantService assistant;
    private final SupportCaller caller;
    private final GuestKeys guestKeys;

    AssistantController(AssistantService assistant, SupportCaller caller, GuestKeys guestKeys) {
        this.assistant = assistant;
        this.caller = caller;
        this.guestKeys = guestKeys;
    }

    @Schema(name = "SupportAssistantQuestion")
    record AskRequest(
            UUID conversationId,
            @Schema(requiredMode = RequiredMode.REQUIRED, maxLength = AssistantService.MAX_QUESTION_CHARS)
            String question) {
    }

    @Operation(operationId = "askSupportAssistant")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed, question-too-long",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "assistant-quota (retryAt)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "support-disabled, assistant-disabled, assistant-paused (retryAt), assistant-unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    AssistantReply ask(@AuthenticationPrincipal Jwt jwt, @RequestBody AskRequest body, Locale locale) {
        return assistant.ask(member(jwt), body.conversationId(), body.question(), locale);
    }

    @Operation(operationId = "getSupportAssistantLatestConversation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/conversations/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationView latest(@AuthenticationPrincipal Jwt jwt) {
        return assistant.latest(member(jwt)).orElseThrow(AssistantService::conversationNotFound);
    }

    @SecurityRequirements
    @Operation(operationId = "askSupportAssistantAsGuest")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (also for a missing or invalid "
            + SESSION_HEADER + "), question-too-long",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "assistant-quota (retryAt)",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503",
            description = "support-disabled, assistant-disabled, assistant-paused (retryAt), assistant-unavailable",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/guest/messages", produces = MediaType.APPLICATION_JSON_VALUE)
    AssistantReply guestAsk(
            @Parameter(required = true, description = "Version 4 UUID the browser keeps for the guest",
                    schema = @Schema(format = "uuid"))
            @RequestHeader(name = SESSION_HEADER, required = false) String session,
            HttpServletRequest request, @RequestBody AskRequest body, Locale locale) {
        return assistant.ask(guest(session, request), body.conversationId(), body.question(), locale);
    }

    @SecurityRequirements
    @Operation(operationId = "getSupportAssistantGuestLatestConversation")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "400", description = "validation-failed (missing or invalid " + SESSION_HEADER + ")",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "404", description = "conversation-not-found",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "503", description = "support-disabled",
            content = @Content(mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE,
                    schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/guest/conversations/latest", produces = MediaType.APPLICATION_JSON_VALUE)
    ConversationView guestLatest(
            @Parameter(required = true, description = "Version 4 UUID the browser keeps for the guest",
                    schema = @Schema(format = "uuid"))
            @RequestHeader(name = SESSION_HEADER, required = false) String session,
            HttpServletRequest request) {
        String validSession = validSession(session);
        if (!guestKeys.configured()) {
            throw AssistantService.conversationNotFound();
        }
        return assistant.latest(guestKeys.guest(validSession, request.getRemoteAddr()))
                .orElseThrow(AssistantService::conversationNotFound);
    }

    private Asker member(Jwt jwt) {
        return new Asker.Member(caller.require(jwt).id());
    }

    private Asker guest(String session, HttpServletRequest request) {
        return guestKeys.guest(validSession(session), request.getRemoteAddr());
    }

    /** The canonical lowercase form of a version-4 UUID, or 400. */
    static String validSession(String session) {
        if (session != null && session.length() == 36) {
            try {
                UUID id = UUID.fromString(session);
                if (id.version() == 4 && id.variant() == 2) {
                    return id.toString();
                }
            } catch (IllegalArgumentException ignored) {
                // falls through to the refusal below
            }
        }
        throw new SupportProblemException(HttpStatus.BAD_REQUEST, "validation-failed",
                new LocalizedMessage("support.session.invalid", SESSION_HEADER));
    }
}
