package com.flowcortex.user.pat;

import com.flowcortex.shared.security.PersonalAccessTokenAuthenticator;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates, lists, revokes and authenticates personal access tokens. The raw token reaches nothing
 * but the create response: not the database, the audit trail, a log line or an error message.
 */
@Service
class PersonalAccessTokenService implements PersonalAccessTokenAuthenticator {

    /** A principal built from a token lives at most this long, so a revoked token is dropped quickly. */
    private static final Duration PRINCIPAL_TTL = Duration.ofSeconds(60);

    static final String PAT_ID_CLAIM = "pat_id";

    private final PersonalAccessTokenRepository tokens;
    private final AppUserRepository users;
    private final StepUpProofs stepUpProofs;
    private final AuditEventWriter audit;
    private final PersonalAccessTokenProperties properties;
    private final Clock clock;

    PersonalAccessTokenService(PersonalAccessTokenRepository tokens, AppUserRepository users,
            StepUpProofs stepUpProofs, AuditEventWriter audit, PersonalAccessTokenProperties properties, Clock clock) {
        this.tokens = tokens;
        this.users = users;
        this.stepUpProofs = stepUpProofs;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    record CreatedToken(PersonalAccessToken token, String rawToken) {
    }

    record TokenSummary(UUID id, String name, Instant createdAt, Instant expiresAt, Instant lastUsedAt,
            boolean expired) {
    }

    /**
     * One transaction: the owner's row is locked first so two creates cannot both pass the cap, the
     * cap is checked before the proof is spent so a refusal for any other reason leaves it usable.
     *
     * @throws PatNotEligibleException when the owner is not an active, unlocked admin
     * @throws PatLimitReachedException at the live-token cap
     * @throws com.flowcortex.user.api.StepUpRequiredException when the proof is missing, spent, expired or not theirs
     */
    @Transactional
    CreatedToken create(UUID userId, String name, Integer expiresInDays, String stepUpProof, String ip,
            String userAgent) {
        Instant now = clock.instant();
        AppUser owner = users.findByIdForUpdate(userId).orElseThrow(PatNotEligibleException::new);
        requireEligible(owner, now);
        if (tokens.countLive(userId, now) >= properties.maxLivePerUser()) {
            throw new PatLimitReachedException();
        }
        stepUpProofs.consume(userId, StepUpPurpose.PAT_CREATE, stepUpProof);

        Duration lifetime = expiresInDays == null ? properties.defaultLifetime() : Duration.ofDays(expiresInDays);
        String raw = PersonalAccessTokenAuthenticator.PREFIX + OpaqueTokens.generateRawToken();
        PersonalAccessToken token = new PersonalAccessToken(UUID.randomUUID(), userId, name,
                OpaqueTokens.hash(raw), now, now.plus(lifetime));
        tokens.save(token);
        record(AuthEventType.PAT_CREATED, userId, token, ip, userAgent);
        return new CreatedToken(token, raw);
    }

    @Transactional(readOnly = true)
    List<TokenSummary> list(UUID userId) {
        Instant now = clock.instant();
        requireEligible(users.findById(userId).orElseThrow(PatNotEligibleException::new), now);
        return tokens.findByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).stream()
                .map(token -> new TokenSummary(token.getId(), token.getName(), token.getCreatedAt(),
                        token.getExpiresAt(), token.getLastUsedAt(), !token.getExpiresAt().isAfter(now)))
                .toList();
    }

    /** @throws NotFoundException when the id is unknown, another account's, or already revoked */
    @Transactional
    void revoke(UUID userId, UUID tokenId, String ip, String userAgent) {
        PersonalAccessToken token = tokens.findByIdAndUserIdAndRevokedAtIsNull(tokenId, userId)
                .orElseThrow(() -> notFound(tokenId));
        if (tokens.revoke(tokenId, userId, clock.instant()) == 0) {
            throw notFound(tokenId);
        }
        record(AuthEventType.PAT_REVOKED, userId, token, ip, userAgent);
    }

    /**
     * Runs inside the shared JWT decoder, outside any MVC or service transaction, hence its own.
     * Every refusal is an empty result: the owner's role, status and the switch are read on each call
     * so a demoted or suspended admin's tokens stop working on the next request.
     */
    @Override
    @Transactional
    public Optional<Jwt> authenticate(String rawToken) {
        if (!properties.enabled() || rawToken == null || !rawToken.startsWith(PREFIX)) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        Optional<PersonalAccessToken> found = tokens.findByTokenHash(OpaqueTokens.hash(rawToken))
                .filter(token -> token.isLiveAt(now));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        PersonalAccessToken token = found.get();
        Optional<AppUser> owner = users.findById(token.getUserId()).filter(user -> PatEligibility.eligible(
                user.getRole(), user.getStatus(), user.getLockedUntil(), now, PatUse.AUTHENTICATE));
        if (owner.isEmpty()) {
            return Optional.empty();
        }
        tokens.touchLastUsed(token.getId(), now);
        Instant expiresAt = token.getExpiresAt().isBefore(now.plus(PRINCIPAL_TTL)) ? token.getExpiresAt()
                : now.plus(PRINCIPAL_TTL);
        return Optional.of(Jwt.withTokenValue("pat:" + token.getId())
                .header("alg", "none")
                .subject(token.getUserId().toString())
                .issuedAt(now)
                .expiresAt(expiresAt)
                .claim(AUTH_CLAIM, AUTH_PAT)
                .claim(PAT_ID_CLAIM, token.getId().toString())
                .build());
    }

    private static void requireEligible(AppUser owner, Instant now) {
        if (!PatEligibility.eligible(owner.getRole(), owner.getStatus(), owner.getLockedUntil(), now,
                PatUse.MANAGE)) {
            throw new PatNotEligibleException();
        }
    }

    private static NotFoundException notFound(UUID tokenId) {
        return new NotFoundException(new LocalizedMessage("pat.notFound", tokenId));
    }

    private void record(AuthEventType type, UUID userId, PersonalAccessToken token, String ip, String userAgent) {
        audit.write(type, userId, ip, userAgent, AuthEventType.metadata("userId", userId.toString(), "tokenId",
                token.getId().toString(), "name", token.getName()));
    }
}
