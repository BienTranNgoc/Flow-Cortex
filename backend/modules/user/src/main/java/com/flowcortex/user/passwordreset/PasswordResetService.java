package com.flowcortex.user.passwordreset;

import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.application.RefreshTokenService;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.emailconfirm.EmailConfirmationService;
import com.flowcortex.user.emailconfirm.EmailConfirmationService.LinkRequest;
import com.flowcortex.user.infrastructure.AppUserRepository;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.ExistingLink;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.GoogleNotice;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.Outcome;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.ResetLink;
import com.flowcortex.user.passwordreset.ResetRequestPolicy.Skip;
import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Forgot-password requests and the resets their links complete. Callers pass a normalised email and
 * an already-encoded password, so no BCrypt runs while the account row is locked. Log lines carry the
 * user id, never the email, the token or the link.
 */
@Service
public class PasswordResetService {

    private static final Logger LOG = LoggerFactory.getLogger(PasswordResetService.class);

    static final String LINK_INVALID_DETAIL = "This reset link is no longer valid";

    static final String LINK_INVALID_CODE = "RESET_LINK_INVALID";

    /** The account whose password a reset link just changed. */
    public record CompletedReset(UUID userId, String email) {
    }

    private final AppUserRepository users;
    private final EmailConfirmationService emailLinks;
    private final RefreshTokenService refreshTokens;
    private final PasswordResetProperties properties;
    private final Clock clock;

    /** Fails startup when reset is on and a link could not be built or mailed. */
    PasswordResetService(AppUserRepository users, EmailConfirmationService emailLinks,
            RefreshTokenService refreshTokens, PasswordResetProperties properties, Clock clock,
            @Value("${flowcortex.app.public-url:}") String publicUrl, @Value("${spring.mail.host:}") String mailHost) {
        if (properties.enabled()) {
            requireSet(publicUrl, "flowcortex.app.public-url (FLOWCORTEX_APP_PUBLIC_URL), the member app origin"
                    + " the reset link opens");
            requireSet(mailHost, "spring.mail.host (SPRING_MAIL_HOST), the SMTP server reset mail goes to");
        }
        this.users = users;
        this.emailLinks = emailLinks;
        this.refreshTokens = refreshTokens;
        this.properties = properties;
        this.clock = clock;
    }

    private static void requireSet(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("flowcortex.auth.password-reset.enabled is true but " + what
                    + " is not set.");
        }
    }

    /**
     * Mails what {@link ResetRequestPolicy} decides for the email's account, after commit. The answer
     * to the visitor must not depend on the outcome; it is returned for the audit trail only.
     */
    @Transactional
    public Outcome request(String normalizedEmail) {
        Optional<AppUser> account = users.findIdByEmail(normalizedEmail).flatMap(users::findByIdForUpdate);
        Outcome outcome = ResetRequestPolicy.decide(account);
        switch (outcome) {
            case ResetLink link -> {
                emailLinks.mailPasswordReset(account.get(), properties.validity());
                LOG.info("password_reset outcome=sent userId={}", link.userId());
            }
            case ExistingLink link -> {
                emailLinks.reissueLinkFor(account.get(), LinkRequest.PASSWORD_RESET);
                LOG.info("password_reset outcome={} userId={}",
                        account.get().isEmailConfirmed() ? "password_link" : "confirmation_link", link.userId());
            }
            case GoogleNotice notice -> {
                emailLinks.mailGoogleSignInNotice(account.get());
                LOG.info("password_reset outcome=google_notice userId={}", notice.userId());
            }
            case Skip skip -> {
                if (account.isPresent()) {
                    LOG.info("password_reset outcome=skipped reason={} userId={}", skip.reason(),
                            account.get().getId());
                } else {
                    LOG.info("password_reset outcome=skipped reason={}", skip.reason());
                }
            }
        }
        return outcome;
    }

    /**
     * Whether the token is still an open reset link, read without the row lock so a guessed token costs no
     * password hashing; {@link #reset} still decides whether the link can be spent.
     */
    @Transactional(readOnly = true)
    public boolean isOpenResetLink(String rawToken) {
        boolean open = emailLinks.isOpenPasswordReset(rawToken);
        if (!open) {
            LOG.info("password_reset outcome=link_invalid");
        }
        return open;
    }

    /**
     * Spends the reset link and sets {@code passwordHash} as the account's password; every session and
     * every other open emailed link of the account ends with it, and any lockout is cleared. Returns the
     * account's id and email.
     *
     * @throws UnprocessableEntityException {@code RESET_LINK_INVALID} when the link cannot be spent or
     *     its account is no longer active; nothing is changed
     */
    @Transactional
    public CompletedReset reset(String rawToken, String passwordHash) {
        Optional<AppUser> spent = emailLinks.spendPasswordReset(rawToken);
        if (spent.isEmpty()) {
            LOG.info("password_reset outcome=link_invalid");
            throw linkInvalid();
        }
        AppUser account = spent.get();
        if (account.getStatus() != UserStatus.ACTIVE) {
            // Thrown, not returned: the rollback also undoes the spend above.
            LOG.info("password_reset outcome=link_invalid reason=inactive userId={}", account.getId());
            throw linkInvalid();
        }
        account.changePasswordHash(passwordHash);
        account.resetFailedLoginAttempts(Instant.now(clock));
        int retired = emailLinks.retireOpenLinks(account.getId());
        refreshTokens.revokeAllFor(account.getId());
        emailLinks.mailPasswordChangedNotice(account);
        LOG.info("password_reset outcome=completed userId={} retiredLinks={}", account.getId(), retired);
        return new CompletedReset(account.getId(), account.getEmail());
    }

    /** The 422 {@code RESET_LINK_INVALID} answer for a link that cannot be spent. */
    public static UnprocessableEntityException linkInvalid() {
        return new UnprocessableEntityException(LINK_INVALID_DETAIL, LINK_INVALID_CODE);
    }
}
