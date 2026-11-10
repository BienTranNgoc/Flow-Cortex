package com.flowcortex.mail.web;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.links.LinkSource;
import com.flowcortex.mail.links.UnsubscribeLinks;
import com.flowcortex.mail.links.UnsubscribeTokens;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.Schema.RequiredMode;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Optional;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The public routes behind an unsubscribe link: view it, press its button, or let a mailbox post a one-click
 * request. Every call spends the same rate-limit bucket before the token is looked at, and a link that cannot
 * be used always answers the same 404.
 */
@RestController
@RequestMapping("/api/v1/email-links")
class EmailLinksController {

    private final UnsubscribeLinks links;
    private final UnsubscribeTokens tokens;
    private final EmailLinkRateLimiter limiter;
    private final LinkActionLog actions;

    EmailLinksController(UnsubscribeLinks links, UnsubscribeTokens tokens, EmailLinkRateLimiter limiter,
            LinkActionLog actions) {
        this.links = links;
        this.tokens = tokens;
        this.limiter = limiter;
        this.actions = actions;
    }

    @SecurityRequirements
    @Operation(operationId = "viewEmailLink")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "mail.link.invalid", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "mail.link.rateLimited", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @GetMapping(value = "/{token}", produces = MediaType.APPLICATION_JSON_VALUE)
    EmailLinkView view(@PathVariable String token, HttpServletRequest request) {
        admit(token, request);
        UnsubscribeLinks.Viewed viewed = links.view(token).orElseThrow(() -> invalid());
        actions.record(LinkActionLog.Outcome.VIEWED, viewed.list(), null, null, viewed.memberId());
        return new EmailLinkView(true, viewed.list().wire(), viewed.unsubscribed(), viewed.maskedEmail());
    }

    @SecurityRequirements
    @Operation(operationId = "unsubscribeEmailLink")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "mail.link.invalid", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "mail.link.rateLimited", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{token}/unsubscribe", produces = MediaType.APPLICATION_JSON_VALUE)
    EmailLinkUnsubscribed unsubscribe(@PathVariable String token,
            @RequestParam(name = "src", required = false) String src, HttpServletRequest request) {
        UnsubscribeLinks.Applied applied = apply(token, LinkSource.FOOTER_LINK, src, request);
        return new EmailLinkUnsubscribed(true, applied.list().wire(), applied.maskedEmail());
    }

    /** Takes no request body at all, so whatever a mailbox posts (a form, JSON, nothing) is accepted. */
    @SecurityRequirements
    @Operation(operationId = "oneClickUnsubscribeEmailLink")
    @ApiResponse(responseCode = "200")
    @ApiResponse(responseCode = "404", description = "mail.link.invalid", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @ApiResponse(responseCode = "429", description = "mail.link.rateLimited", content = @Content(
            mediaType = MediaType.APPLICATION_PROBLEM_JSON_VALUE, schema = @Schema(implementation = ProblemDetail.class)))
    @PostMapping(value = "/{token}/one-click", produces = MediaType.APPLICATION_JSON_VALUE)
    EmailLinkOneClick oneClick(@PathVariable String token, @RequestParam(name = "src", required = false) String src,
            HttpServletRequest request) {
        apply(token, LinkSource.ONE_CLICK, src, request);
        return new EmailLinkOneClick(true);
    }

    private UnsubscribeLinks.Applied apply(String token, LinkSource source, String src, HttpServletRequest request) {
        admit(token, request);
        UnsubscribeLinks.Applied applied = links.apply(token, source, src).orElseThrow(() -> invalid());
        actions.record(applied.changed() ? LinkActionLog.Outcome.APPLIED : LinkActionLog.Outcome.ALREADY,
                applied.list(), source, catalogKey(src).orElse(null), applied.memberId());
        return applied;
    }

    /** Spends the bucket before any lookup; a refusal reads the list from the signature alone, no database. */
    private void admit(String token, HttpServletRequest request) {
        Optional<Duration> refusal = limiter.tryAcquire(sha256(token), request.getRemoteAddr());
        if (refusal.isPresent()) {
            actions.record(LinkActionLog.Outcome.RATE_LIMITED,
                    tokens.verify(token).map(UnsubscribeTokens.Verified::list).orElse(null), null, null, null);
            throw new EmailLinkRateLimitedException(refusal.get());
        }
    }

    private EmailLinkInvalidException invalid() {
        actions.record(LinkActionLog.Outcome.INVALID, null, null, null, null);
        return new EmailLinkInvalidException();
    }

    /** The key id when {@code src} names a catalog key; anything else is dropped, never logged or stored. */
    private static Optional<String> catalogKey(String src) {
        return src == null ? Optional.empty() : MailKey.fromId(src).map(MailKey::id);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    @Schema(name = "EmailLinkView")
    record EmailLinkView(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean valid,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"care", "marketing"}) String list,
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean unsubscribed,
            @Schema(requiredMode = RequiredMode.REQUIRED) String maskedEmail) {
    }

    @Schema(name = "EmailLinkUnsubscribed")
    record EmailLinkUnsubscribed(
            @Schema(requiredMode = RequiredMode.REQUIRED) boolean unsubscribed,
            @Schema(requiredMode = RequiredMode.REQUIRED, allowableValues = {"care", "marketing"}) String list,
            @Schema(requiredMode = RequiredMode.REQUIRED) String maskedEmail) {
    }

    @Schema(name = "EmailLinkOneClick")
    record EmailLinkOneClick(@Schema(requiredMode = RequiredMode.REQUIRED) boolean ok) {
    }
}
