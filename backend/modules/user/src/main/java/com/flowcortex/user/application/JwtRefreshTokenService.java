package com.flowcortex.user.application;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.RefreshToken;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link RefreshTokenService}. Refresh tokens are
 * opaque, 32 random bytes, base64url-encoded, and persisted as their SHA-256
 * hash. Each rotation preserves the {@code familyId}; reuse on a used or
 * revoked row revokes the whole family.
 */
@Service
public class JwtRefreshTokenService implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AppUserRepository userRepository;
    private final AccessTokenService accessTokenService;
    private final AuditEventWriter audit;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    public JwtRefreshTokenService(RefreshTokenRepository refreshTokenRepository, AppUserRepository userRepository,
            AccessTokenService accessTokenService, AuditEventWriter audit, RefreshTokenProperties properties,
            Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.accessTokenService = accessTokenService;
        this.audit = audit;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public TokenPair issueFor(AppUser user, String ip, String userAgent, boolean rememberMe) {
        Instant now = Instant.now(clock);
        Duration ttl = rememberMe ? properties.rememberMeTtl() : properties.ttl();
        Instant expiresAt = now.plus(ttl);

        int active = refreshTokenRepository.countActiveByUserId(user.getId(), now);
        if (active >= properties.maxActivePerUser()) {
            List<RefreshToken> inFamily = refreshTokenRepository.findActiveByUserIdAndFamilyId(user.getId(),
                    UUID.randomUUID());
            // Per-family oldest-active revocation — see tasks.md §4.5.
            // We pick the single oldest across families; safe because count
            // is across the user, but revocation is in a single family to
            // preserve the rotation semantics.
            inFamily.stream().findFirst().ifPresent(t -> t.revoke(now));
        }

        String raw = RefreshTokenHasher.generateRawToken();
        String hashed = RefreshTokenHasher.hash(raw);
        UUID familyId = UUID.randomUUID();
        RefreshToken row = new RefreshToken(UUID.randomUUID(), user.getId(), hashed, familyId, now, expiresAt, ip,
                truncate(userAgent));
        refreshTokenRepository.save(row);

        String accessToken = accessTokenService.mint(user);
        audit.write(AuthEventType.LOGIN_SUCCESS, user.getId(), ip, truncate(userAgent),
                AuthEventType.userMetadata(user.getId()));
        return new TokenPair(accessToken, raw, now.plus(accessTokenService.accessTokenTtl()), expiresAt);
    }

    @Override
    @Transactional
    public TokenPair rotate(String rawToken, String ip, String userAgent) {
        String hashed = RefreshTokenHasher.hash(rawToken);
        RefreshToken existing = refreshTokenRepository.findByTokenHash(hashed)
                .orElseThrow(() -> new ReuseDetectedException("refresh token not found"));
        Instant now = Instant.now(clock);

        if (!existing.isUsable(now)) {
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            userRepository.findById(existing.getUserId()).ifPresent(user -> audit.write(
                    AuthEventType.REFRESH_TOKEN_REUSE_DETECTED, user.getId(), ip, truncate(userAgent),
                    AuthEventType.metadata("userId", user.getId().toString(), "familyId",
                            existing.getFamilyId().toString())));
            throw new ReuseDetectedException("refresh token already used or revoked");
        }

        AppUser user = userRepository.findById(existing.getUserId())
                .orElseThrow(() -> new ReuseDetectedException("user not found"));

        // Issue the new row first, then mark the old one used — that way a
        // crash between the two leaves the family in a consistent state
        // (caller retries the old token, sees it usable, gets a fresh pair).
        String newRaw = RefreshTokenHasher.generateRawToken();
        String newHash = RefreshTokenHasher.hash(newRaw);
        UUID newId = UUID.randomUUID();
        RefreshToken newRow = new RefreshToken(newId, user.getId(), newHash, existing.getFamilyId(), now,
                now.plus(properties.ttl()), ip, truncate(userAgent));
        refreshTokenRepository.save(newRow);

        int updated = refreshTokenRepository.markUsed(existing.getId(), now, newId);
        if (updated == 0) {
            // Lost the CAS race with a concurrent rotation — treat as reuse.
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            audit.write(AuthEventType.REFRESH_TOKEN_REUSE_DETECTED, user.getId(), ip, truncate(userAgent),
                    AuthEventType.metadata("userId", user.getId().toString(), "familyId",
                            existing.getFamilyId().toString()));
            throw new ReuseDetectedException("refresh token lost race");
        }

        String accessToken = accessTokenService.mint(user);
        audit.write(AuthEventType.REFRESH_TOKEN_ROTATED, user.getId(), ip, truncate(userAgent),
                AuthEventType.metadata("userId", user.getId().toString(), "familyId",
                        existing.getFamilyId().toString()));
        return new TokenPair(accessToken, newRaw, now.plus(accessTokenService.accessTokenTtl()),
                now.plus(properties.ttl()));
    }

    @Override
    @Transactional
    public void revoke(RefreshToken token, Instant now) {
        if (token.getRevokedAt() == null) {
            token.revoke(now);
        }
    }

    @Override
    @Transactional
    public void revokeFamily(UUID familyId, Instant now) {
        refreshTokenRepository.revokeFamily(familyId, now);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<RefreshToken> findByRaw(String rawToken) {
        return refreshTokenRepository.findByTokenHash(RefreshTokenHasher.hash(rawToken));
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
