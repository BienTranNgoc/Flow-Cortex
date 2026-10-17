package com.flowcortex.user.application;

import com.flowcortex.user.api.AuthSession;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.RefreshToken;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.infrastructure.RefreshTokenRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Default implementation of {@link RefreshTokenService}. Refresh tokens are
 * opaque, 32 random bytes, base64url-encoded, and persisted as their SHA-256
 * hash. Each rotation preserves the {@code familyId}, the family's expiry and its
 * remember-me flag; reuse of an already-used row revokes the whole family.
 */
@Service
public class JwtRefreshTokenService implements RefreshTokenService {

    private final RefreshTokenRepository refreshTokenRepository;
    private final AppUserRepository userRepository;
    private final AccessTokenService accessTokenService;
    private final RefreshTokenProperties properties;
    private final Clock clock;

    public JwtRefreshTokenService(RefreshTokenRepository refreshTokenRepository, AppUserRepository userRepository,
            AccessTokenService accessTokenService, RefreshTokenProperties properties, Clock clock) {
        this.refreshTokenRepository = refreshTokenRepository;
        this.userRepository = userRepository;
        this.accessTokenService = accessTokenService;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    @Transactional
    public AuthSession issueFor(AppUser user, String ip, String userAgent, boolean rememberMe) {
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(rememberMe ? properties.rememberMeTtl() : properties.ttl());

        revokeOldestBeyondCap(user.getId(), now);

        String raw = RefreshTokenHasher.generateRawToken();
        RefreshToken row = new RefreshToken(UUID.randomUUID(), user.getId(), RefreshTokenHasher.hash(raw),
                UUID.randomUUID(), now, expiresAt, rememberMe, ip, truncate(userAgent));
        refreshTokenRepository.save(row);
        return session(user, raw, expiresAt, rememberMe);
    }

    @Override
    @Transactional
    public RotationResult rotate(String rawToken, String ip, String userAgent) {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(RefreshTokenHasher.hash(rawToken));
        if (found.isEmpty()) {
            return rejected(RotationResult.Reason.UNKNOWN_TOKEN, null);
        }
        RefreshToken existing = found.get();
        Instant now = Instant.now(clock);

        if (existing.getUsedAt() != null) {
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            return rejected(RotationResult.Reason.REUSED, existing);
        }
        if (existing.getRevokedAt() != null) {
            return rejected(RotationResult.Reason.REVOKED, existing);
        }
        if (existing.isExpired(now)) {
            return rejected(RotationResult.Reason.EXPIRED, existing);
        }
        Optional<AppUser> owner = userRepository.findById(existing.getUserId());
        if (owner.isEmpty() || !AccountStatusGuard.canSignIn(owner.get(), now)) {
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            return rejected(RotationResult.Reason.ACCOUNT_NOT_ALLOWED, existing);
        }
        AppUser user = owner.get();

        String newRaw = RefreshTokenHasher.generateRawToken();
        UUID newId = UUID.randomUUID();
        refreshTokenRepository.save(new RefreshToken(newId, user.getId(), RefreshTokenHasher.hash(newRaw),
                existing.getFamilyId(), now, existing.getExpiresAt(), existing.isRememberMe(), ip,
                truncate(userAgent)));

        if (refreshTokenRepository.markUsed(existing.getId(), now, newId) == 0) {
            // Lost the CAS race with a concurrent rotation of the same token — treat as reuse.
            refreshTokenRepository.revokeFamily(existing.getFamilyId(), now);
            return rejected(RotationResult.Reason.REUSED, existing);
        }
        return new RotationResult.Rotated(session(user, newRaw, existing.getExpiresAt(), existing.isRememberMe()),
                user.getId(), existing.getFamilyId());
    }

    @Override
    @Transactional
    public Optional<UUID> endSession(String rawToken) {
        Optional<RefreshToken> found = refreshTokenRepository.findByTokenHash(RefreshTokenHasher.hash(rawToken));
        found.ifPresent(token -> refreshTokenRepository.revokeFamily(token.getFamilyId(), Instant.now(clock)));
        return found.map(RefreshToken::getUserId);
    }

    @Override
    @Transactional
    public void revokeAllFor(UUID userId) {
        refreshTokenRepository.revokeAllByUserId(userId, Instant.now(clock));
    }

    /** Leaves room for the session about to be created: the cap counts it. */
    private void revokeOldestBeyondCap(UUID userId, Instant now) {
        List<RefreshToken> active = refreshTokenRepository.findActiveByUserId(userId, now);
        int excess = active.size() - properties.maxActivePerUser() + 1;
        for (int i = 0; i < excess; i++) {
            refreshTokenRepository.revokeFamily(active.get(i).getFamilyId(), now);
        }
    }

    private AuthSession session(AppUser user, String rawRefreshToken, Instant refreshExpiresAt, boolean rememberMe) {
        return new AuthSession(accessTokenService.mint(user), accessTokenService.accessTokenTtl().toSeconds(),
                rawRefreshToken, refreshExpiresAt, rememberMe);
    }

    private static RotationResult.Rejected rejected(RotationResult.Reason reason, RefreshToken token) {
        return new RotationResult.Rejected(reason, token == null ? null : token.getUserId(),
                token == null ? null : token.getFamilyId());
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 512 ? value : value.substring(0, 512);
    }
}
