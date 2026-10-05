package com.flowcortex.user.application;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.NotFoundException;
import com.flowcortex.user.api.AuthService;
import com.flowcortex.user.api.CurrentUser;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.Role;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Implementation of {@link AuthService}.
 */
@Service
class AuthServiceImpl implements AuthService {

    private static final Logger LOG = LoggerFactory.getLogger(AuthServiceImpl.class);

    /**
     * Token lifetime — no refresh flow exists yet; a fixed, generous session
     * length is the simplest choice that does not force a re-login mid-demo.
     */
    private static final Duration TOKEN_TTL = Duration.ofHours(12);

    private static final String ISSUER = "flow-cortex";

    /** Same message for "unknown email" and "wrong password" — never discloses which. */
    private static final String INVALID_CREDENTIALS = "Invalid email or password";

    private static final String REGISTRATION_CLOSED = "Self-registration is not available";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtEncoder jwtEncoder;
    private final RegistrationProperties registration;

    AuthServiceImpl(AppUserRepository userRepository, PasswordEncoder passwordEncoder, JwtEncoder jwtEncoder,
            RegistrationProperties registration) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtEncoder = jwtEncoder;
        this.registration = registration;
    }

    @Override
    @Transactional
    public String register(String email, String rawPassword, String name) {
        AppUser owner = requireRegistrationOwner();
        AppUser user = new AppUser(UUID.randomUUID(), owner.getId(), email, passwordEncoder.encode(rawPassword), name,
                Role.MEMBER, UserStatus.ACTIVE);
        try {
            userRepository.saveAndFlush(user);
        } catch (DataIntegrityViolationException ex) {
            throw new ConflictException("A user with email '" + email + "' already exists");
        }
        return mintToken(user);
    }

    /**
     * The configured registration owner, read on every attempt so a suspended or demoted owner
     * closes registration at once; anything but an active ADMIN closes it.
     */
    private AppUser requireRegistrationOwner() {
        String ownerEmail = registration.ownerEmail() == null ? "" : registration.ownerEmail().trim();
        if (ownerEmail.isEmpty()) {
            LOG.info("Registration refused: reason=owner_not_configured");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, REGISTRATION_CLOSED);
        }
        AppUser owner = userRepository.findByEmail(ownerEmail).orElse(null);
        String unusable = owner == null ? "owner_not_found"
                : owner.getRole() != Role.ADMIN ? "owner_not_admin"
                : owner.getStatus() != UserStatus.ACTIVE ? "owner_not_active"
                : null;
        if (unusable != null) {
            LOG.warn("Registration refused: reason={} (check flowcortex.registration.owner-email)", unusable);
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, REGISTRATION_CLOSED);
        }
        return owner;
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
