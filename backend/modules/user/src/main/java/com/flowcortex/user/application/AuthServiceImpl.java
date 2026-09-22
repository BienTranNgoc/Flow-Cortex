package com.flowcortex.user.application;

import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of {@link AuthService} (design.md D4).
 */
@Service
class AuthServiceImpl implements AuthService {

    /**
     * [AGENT-ASSUMED] Token lifetime — not specified by design.md/tasks.md
     * (no refresh flow exists this change, proposal — Out of Scope); a fixed,
     * generous session length is the simplest choice that does not force a
     * re-login mid-demo.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private static final String ISSUER = "flow-cortex";

    /** Same message for "unknown email" and "wrong password" — never discloses which. */
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;

    AuthServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
    }

    @Override
    @Transactional(readOnly = true)
    public String login(String email, String rawPassword) {
        AppUser user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException(INVALID_CREDENTIALS));
        if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw new BadCredentialsException(INVALID_CREDENTIALS);
        }
        AccountStatusGuard.requireActive(user);
        return mintToken(user);
    }

    @Override
    @Transactional
    public CurrentUser updateOwnProfile(UUID userId, String name) {
        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found: " + userId));
        AccountStatusGuard.requireActive(user);
        user.changeName(name);
        return AppUserMapper.toCurrentUser(user);
    }

    private String mintToken(AppUser user) {
        Instant now = Instant.now();
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(ISSUER)
                .issuedAt(now)
                .expiresAt(now.plus(TOKEN_TTL))
                .subject(user.getId().toString())
                .claim(JwtClaims.ROLE, user.getRole().name())
                .build();
        return jwtEncoder.encode(JwtEncoderParameters.from(claims)).getTokenValue();
    }
}
