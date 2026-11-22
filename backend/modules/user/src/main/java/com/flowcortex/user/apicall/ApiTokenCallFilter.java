package com.flowcortex.user.apicall;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import com.flowcortex.user.apicall.ApiTokenCall.Outcome;
import com.flowcortex.user.apicall.IdempotencyStore.Entry;
import com.flowcortex.user.audit.AuditErrorCode;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.security.autoconfigure.web.servlet.SecurityFilterProperties;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.HandlerMapping;
import org.springframework.web.util.ContentCachingResponseWrapper;

/**
 * Runs inside the security chain's request, once the principal is set, and acts only on writes sent
 * with a personal access token. In order: a keyed write is reserved or answered from its stored
 * answer, the optional daily cap may refuse, the handler runs, and one call row is written after the
 * response is complete. Nothing in it changes what the handler answered.
 */
@Order(SecurityFilterProperties.DEFAULT_FILTER_ORDER + 10)
class ApiTokenCallFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(ApiTokenCallFilter.class);
    private static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final String PAT_NOT_ALLOWED = "pat.notAllowed";
    private static final int MAX_CONTENT_TYPE = 255;
    private static final int MAX_RESERVE_ATTEMPTS = 3;

    private record Held(UUID userId, String key, Instant reservedAt) {}

    private record Admitted(Entry replay, Held held, HttpServletRequest request) {}

    private final ApiTokenCallsProperties properties;
    private final JdbcApiTokenCallWriter calls;
    private final IdempotencyStore idempotency;
    private final Clock clock;
    private final HandlerExceptionResolver exceptionResolver;

    ApiTokenCallFilter(ApiTokenCallsProperties properties, JdbcApiTokenCallWriter calls, IdempotencyStore idempotency,
            Clock clock, HandlerExceptionResolver exceptionResolver) {
        this.properties = properties;
        this.calls = calls;
        this.idempotency = idempotency;
        this.clock = clock;
        this.exceptionResolver = exceptionResolver;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !ApiTokenCallRoute.acts(request.getMethod(), principal());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Jwt jwt = (Jwt) principal();
        UUID userId = UUID.fromString(jwt.getSubject());
        UUID tokenId = UUID.fromString(jwt.getClaimAsString(PersonalAccessTokenAuthenticator.PAT_ID_CLAIM));
        Instant startedAt = clock.instant();
        long startedNanos = System.nanoTime();
        PendingCall pending = new PendingCall();
        request.setAttribute(PendingCall.ATTRIBUTE, pending);

        Outcome guarded = null;
        Held held = null;
        boolean failed = false;
        try {
            HttpServletRequest downstream = request;
            String rawKey = IdempotencyKey.appliesTo(request.getMethod())
                    ? request.getHeader(IdempotencyKey.HEADER) : null;
            if (rawKey != null) {
                Admitted admitted = admit(request, userId, rawKey);
                if (admitted.replay() != null) {
                    replay(response, admitted.replay());
                    guarded = Outcome.REPLAYED;
                } else {
                    held = admitted.held();
                    downstream = admitted.request();
                }
            }
            if (guarded == null) {
                Optional<Duration> wait = capWait(request, userId, startedAt);
                if (wait.isPresent()) {
                    release(held, request);
                    held = null;
                    refuse(request, response, pending, new ApiTokenDailyLimitException(wait.get()));
                    guarded = Outcome.BLOCKED;
                }
            }
            if (guarded == null) {
                runHandler(request, downstream, response, chain, held);
                held = null;
            }
        } catch (ApiTokenCallProblemException refusal) {
            refuse(request, response, pending, refusal);
        } catch (IOException | ServletException | RuntimeException ex) {
            failed = true;
            release(held, request);
            throw ex;
        } finally {
            int status = failed ? HttpStatus.INTERNAL_SERVER_ERROR.value() : response.getStatus();
            calls.write(row(request, userId, tokenId, guarded, status, pending, startedAt, startedNanos));
        }
    }

    private Admitted admit(HttpServletRequest request, UUID userId, String rawKey) throws IOException {
        String key = IdempotencyKey.normalize(rawKey).orElseThrow(ApiTokenCallProblemException::keyInvalid);
        long max = properties.maxKeyedRequestBody().toBytes();
        if (IdempotencyKey.unsupported(request.getContentType(), request.getContentLengthLong(), max)) {
            throw ApiTokenCallProblemException.unsupported(max / 1024);
        }
        byte[] body = request.getInputStream().readNBytes((int) Math.min(max + 1, Integer.MAX_VALUE));
        if (IdempotencyKey.unsupported(null, body.length, max)) {
            throw ApiTokenCallProblemException.unsupported(max / 1024);
        }
        String fingerprint = IdempotencyKey.fingerprint(request.getMethod(), request.getRequestURI(),
                request.getQueryString(), body);
        HttpServletRequest cached = new CachedBodyRequest(request, body);
        for (int attempt = 0; attempt < MAX_RESERVE_ATTEMPTS; attempt++) {
            Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
            Optional<Entry> existing = idempotency.find(userId, key);
            switch (IdempotencyDecision.decide(existing.map(Entry::row), fingerprint, now,
                    properties.idempotencyStaleAfter())) {
                case REPLAY -> {
                    return new Admitted(existing.orElseThrow(), null, cached);
                }
                case KEY_REUSED -> throw ApiTokenCallProblemException.keyReused();
                case IN_PROGRESS -> throw ApiTokenCallProblemException.inProgress();
                case PROCEED, TAKE_OVER -> {
                    Instant expiresAt = now.plus(properties.idempotencyWindow());
                    boolean reserved = existing.isEmpty()
                            ? idempotency.reserve(userId, key, fingerprint, now, expiresAt)
                            : idempotency.takeOver(userId, key, existing.get().row().reservedAt(), fingerprint, now,
                                    expiresAt);
                    if (reserved) {
                        return new Admitted(null, new Held(userId, key, now), cached);
                    }
                }
                default -> throw new IllegalStateException("Unhandled idempotency decision");
            }
        }
        throw ApiTokenCallProblemException.inProgress();
    }

    /** A cap that cannot be counted does not stop the write: the count is advisory, the handler is not. */
    private Optional<Duration> capWait(HttpServletRequest request, UUID userId, Instant now) {
        boolean create = ApiTokenCallRoute.isWorkflowCreate(request.getMethod(), request.getRequestURI());
        int limitTotal = properties.dailyWriteLimit();
        int limitCreate = properties.dailyWorkflowCreateLimit();
        if (!DailyWriteCap.needsCount(limitTotal, limitCreate, create)) {
            return Optional.empty();
        }
        try {
            JdbcApiTokenCallWriter.DayCounts counts = calls.countSince(userId, DailyWriteCap.startOfDay(now));
            return DailyWriteCap.decide(limitTotal, limitCreate, create, counts.total(), counts.workflowCreates(),
                    now);
        } catch (DataAccessException ex) {
            LOG.error("API token daily cap not enforced for {} {}", request.getMethod(),
                    ApiTokenCallRoute.normalize(request.getRequestURI()), ex);
            return Optional.empty();
        }
    }

    /** Runs the handler; a keyed write buffers its answer so it can be stored before it reaches the client. */
    private void runHandler(HttpServletRequest request, HttpServletRequest downstream, HttpServletResponse response,
            FilterChain chain, Held held) throws IOException, ServletException {
        if (held == null) {
            chain.doFilter(downstream, response);
            return;
        }
        ContentCachingResponseWrapper capture = new ContentCachingResponseWrapper(response);
        chain.doFilter(downstream, capture);
        store(request, held, capture);
        capture.copyBodyToResponse();
    }

    private void store(HttpServletRequest request, Held held, ContentCachingResponseWrapper capture) {
        int status = capture.getStatus();
        byte[] body = capture.getContentAsByteArray();
        String contentType = capture.getContentType();
        boolean storable = status >= 200 && status < 300 && body.length <= properties.maxStoredResponseBody().toBytes()
                && (contentType == null || contentType.length() <= MAX_CONTENT_TYPE);
        try {
            if (storable) {
                idempotency.complete(held.userId(), held.key(), held.reservedAt(), status, contentType, body);
            } else {
                idempotency.release(held.userId(), held.key(), held.reservedAt());
            }
        } catch (DataAccessException ex) {
            LOG.error("API token idempotency key for {} {} was not settled", request.getMethod(),
                    ApiTokenCallRoute.normalize(request.getRequestURI()), ex);
        }
    }

    private void release(Held held, HttpServletRequest request) {
        if (held == null) {
            return;
        }
        try {
            idempotency.release(held.userId(), held.key(), held.reservedAt());
        } catch (DataAccessException ex) {
            LOG.error("API token idempotency key for {} {} was not released", request.getMethod(),
                    ApiTokenCallRoute.normalize(request.getRequestURI()), ex);
        }
    }

    private void replay(HttpServletResponse response, Entry entry) throws IOException {
        byte[] body = entry.responseBody() == null ? new byte[0] : entry.responseBody();
        response.setStatus(entry.httpStatus() == null ? HttpStatus.OK.value() : entry.httpStatus());
        if (entry.contentType() != null) {
            response.setContentType(entry.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void refuse(HttpServletRequest request, HttpServletResponse response, PendingCall pending,
            RuntimeException problem) {
        pending.exception(problem);
        exceptionResolver.resolveException(request, response, null, problem);
    }

    private ApiTokenCall row(HttpServletRequest request, UUID userId, UUID tokenId, Outcome guarded, int status,
            PendingCall pending, Instant startedAt, long startedNanos) {
        Outcome outcome = guarded != null ? guarded : status >= 400 ? Outcome.ERROR : Outcome.OK;
        String errorCode = outcome == Outcome.ERROR || outcome == Outcome.BLOCKED
                ? errorCode(pending.exception(), status) : null;
        String route = ApiTokenCallRoute.of((String) request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE),
                request.getRequestURI());
        return new ApiTokenCall(userId, tokenId, request.getMethod(), route, outcome, status, errorCode,
                (System.nanoTime() - startedNanos) / 1_000_000, startedAt);
    }

    /** A denial on a token principal is always answered as not-allowed by the personal access token advice. */
    private static String errorCode(Throwable exception, int status) {
        if (exception instanceof AccessDeniedException) {
            return PAT_NOT_ALLOWED;
        }
        return AuditErrorCode.of(exception, status);
    }

    private static Object principal() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication == null ? null : authentication.getPrincipal();
    }
}
