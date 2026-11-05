package com.flowcortex.user.emailconfirm;

import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Example;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Issues, mails and spends the account's one-time tokens ({@link EmailLinkPurpose}): confirmation
 * links, set-password links, password-setup tokens and reset links. A token carries 32 random bytes;
 * only their SHA-256 hex is stored, and neither the token nor a link is ever logged.
 *
 * <p>Mail is handed to the app's task executor after the surrounding transaction commits, so a
 * rolled-back request sends nothing and no request waits on SMTP — an address with an account answers
 * as fast as one without. A send failure or a full queue is logged and counted without failing the
 * request; the member can ask for a new link. A transaction synchronization is used rather than an
 * after-commit event listener because the event publication registry would persist the event, raw
 * token included.
 */
@Service
public class EmailConfirmationService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailConfirmationService.class);

    static final String SUBJECT = "Confirm your Flow-Cortex email";

    static final String PASSWORD_LINK_SUBJECT = "Set your Flow-Cortex password";

    static final String PASSWORD_SET_SUBJECT = "Your Flow-Cortex password was set";

    static final String PASSWORD_RESET_SUBJECT = "Reset your Flow-Cortex password";

    static final String GOOGLE_SIGN_IN_SUBJECT = "Your Flow-Cortex account signs in with Google";

    static final String PASSWORD_CHANGED_SUBJECT = "Your Flow-Cortex password was changed";

    /** Why a link is reissued; the confirmation mail's wording depends on it. */
    public enum LinkRequest {
        RESEND,
        PASSWORD_RESET
    }

    private final EmailConfirmationTokenRepository tokens;
    private final AppUserRepository users;
    private final JavaMailSender mailSender;
    private final TaskExecutor mailExecutor;
    private final EmailConfirmationProperties properties;
    private final Clock clock;
    private final String publicUrl;
    private final Counter sendFailures;

    /** Fails startup when confirmation is on and a link could not be built or mailed. */
    EmailConfirmationService(EmailConfirmationTokenRepository tokens, AppUserRepository users,
            JavaMailSender mailSender, @Qualifier("applicationTaskExecutor") TaskExecutor mailExecutor,
            EmailConfirmationProperties properties, Clock clock,
            @Value("${flowcortex.app.public-url:}") String publicUrl, @Value("${spring.mail.host:}") String mailHost,
            MeterRegistry meters) {
        if (properties.enabled()) {
            requireSet(publicUrl, "flowcortex.app.public-url (FLOWCORTEX_APP_PUBLIC_URL), the member app origin"
                    + " the confirmation link opens");
            requireSet(mailHost, "spring.mail.host (SPRING_MAIL_HOST), the SMTP server confirmation mail goes to");
        }
        this.tokens = tokens;
        this.users = users;
        this.mailSender = mailSender;
        this.mailExecutor = mailExecutor;
        this.properties = properties;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.sendFailures = Counter.builder("email.confirmation.send_failed")
                .description("Account mails (confirmation, set-password and reset links, notices) the SMTP server"
                        + " did not accept, or the send queue had no room for")
                .register(meters);
    }

    private static void requireSet(String value, String what) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("flowcortex.auth.email-confirmation.enabled is true but " + what
                    + " is not set.");
        }
    }

    /** Joins the caller's transaction, so the link is stored only if the account it confirms commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID userId, String email) {
        mailConfirmationLink(userId, email, LinkRequest.RESEND);
    }

    /** Retires the account's open links and issues a new one, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reissue(UUID userId, String email) {
        int retired = retireOpenLinks(userId);
        LOG.info("email_confirmation outcome=reissue userId={} retiredLinks={}", userId, retired);
        mailConfirmationLink(userId, email, LinkRequest.RESEND);
    }

    /** Earlier mails and setup tokens of every purpose stop working; joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public int retireOpenLinks(UUID userId) {
        return tokens.invalidateOpenFor(userId, Instant.now(clock));
    }

    /**
     * Spends the link and confirms its account, dropping what was chosen at registration (see {@link
     * AppUser#confirmEmailDroppingUnprovenChoices}). Empty when the link is unknown, already used,
     * superseded by a newer one, or past its validity.
     */
    @Transactional
    public Optional<Confirmed> confirm(String rawToken) {
        Instant now = Instant.now(clock);
        Optional<AppUser> account = spendLink(rawToken, EmailLinkPurpose.EMAIL_CONFIRM, now);
        if (account.isEmpty()) {
            LOG.info("email_confirmation outcome=link_invalid");
            return Optional.empty();
        }
        AppUser user = account.get();
        boolean firstConfirmation = user.confirmEmailDroppingUnprovenChoices(now, null);
        LOG.info("email_confirmation outcome=confirmed userId={} firstConfirmation={}", user.getId(),
                firstConfirmation);
        return Optional.of(new Confirmed(user, firstConfirmation));
    }

    /** {@code firstConfirmation}: this link confirmed the account, rather than finding it already confirmed. */
    public record Confirmed(AppUser account, boolean firstConfirmation) {
    }

    /**
     * Spends an emailed set-password link. Empty when the link is unknown, used, expired, or its
     * account has a password by now; joins the caller's transaction, which holds the account row lock.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AppUser> openPasswordLink(String rawToken) {
        Optional<AppUser> account = spendLink(rawToken, EmailLinkPurpose.PASSWORD_LINK, Instant.now(clock))
                .filter(user -> !user.hasPassword());
        LOG.info("email_confirmation outcome={}", account.isPresent() ? "password_link_opened"
                : "password_link_invalid");
        return account;
    }

    /**
     * Account row first, then the token: a re-registration, a Google takeover or a password setup of
     * the same account locks in that order too, so none of them deadlocks against this spend.
     */
    private Optional<AppUser> spendLink(String rawToken, EmailLinkPurpose purpose, Instant now) {
        String tokenHash = OpaqueTokens.hash(rawToken);
        Optional<AppUser> account = tokens.findUserIdByTokenHash(tokenHash, purpose)
                .flatMap(users::findByIdForUpdate);
        if (account.isEmpty() || tokens.consume(tokenHash, purpose, account.get().getId(), now) == 0) {
            return Optional.empty();
        }
        return account;
    }

    /**
     * A new password-setup token for the account, returned to the caller rather than mailed. Joins the
     * caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public String issuePasswordSetupToken(UUID userId) {
        return store(userId, EmailLinkPurpose.PASSWORD_SETUP, properties.passwordSetupValidity());
    }

    /** True when this call spent an open setup token of this account. Joins the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean spendPasswordSetupToken(UUID userId, String rawToken) {
        return tokens.consume(OpaqueTokens.hash(rawToken), EmailLinkPurpose.PASSWORD_SETUP, userId, Instant.now(clock)) == 1;
    }

    /** Tells the account's address that a password was set, after the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailPasswordSetNotice(UUID userId, String email) {
        mailAfterCommit(userId, email, PASSWORD_SET_SUBJECT, "Hello,\n\n"
                + "A password was just set for your Flow-Cortex account, and every other signed-in session"
                + " was ended.\n\n"
                + "If this was not you, contact support right away.\n", "password_set_notice");
    }

    /**
     * Mails a new link when the email belongs to an active account that needs one: a confirmation link
     * for an unconfirmed account, a set-password link for a confirmed account without a password. The
     * account's open links are retired first. Otherwise does nothing, and the caller answers the same.
     */
    @Transactional
    public void resend(String email) {
        Optional<AppUser> found = users.findIdByEmail(email).flatMap(users::findByIdForUpdate);
        String skipped = found.isEmpty() ? "unknown_email"
                : found.get().getStatus() != UserStatus.ACTIVE ? "inactive"
                : found.get().isEmailConfirmed() && found.get().hasPassword() ? "already_confirmed"
                : null;
        if (skipped != null) {
            LOG.info("email_confirmation outcome=resend_skipped reason={}", skipped);
            return;
        }
        reissueLinkFor(found.get(), LinkRequest.RESEND);
    }

    /**
     * Retires the account's open links, then mails a confirmation link when unconfirmed or a set-password
     * link when confirmed. The caller holds the account's row lock and has checked it is ACTIVE and needs one.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reissueLinkFor(AppUser locked, LinkRequest request) {
        int retired = retireOpenLinks(locked.getId());
        if (locked.isEmailConfirmed()) {
            LOG.info("email_confirmation outcome=password_link userId={} retiredLinks={}", locked.getId(), retired);
            mailPasswordLink(locked.getId(), locked.getEmail());
        } else {
            LOG.info("email_confirmation outcome=resend userId={} retiredLinks={}", locked.getId(), retired);
            mailConfirmationLink(locked.getId(), locked.getEmail(), request);
        }
    }

    private void mailConfirmationLink(UUID userId, String email, LinkRequest request) {
        String rawToken = store(userId, EmailLinkPurpose.EMAIL_CONFIRM, properties.validity());
        long hours = properties.validity().toHours();
        String link = publicUrl + "/confirm-email?token=" + rawToken + "\n\n";
        String validity = "The link works for " + hours + (hours == 1 ? " hour" : " hours") + " and only once.";
        // Asked for by a reset, so "ignore this email" would be wrong advice.
        String body = request == LinkRequest.PASSWORD_RESET
                ? "You asked to reset your Flow-Cortex password. This address has an account that was never"
                        + " confirmed — confirm it, then choose your own password and name.\n\n"
                        + link + validity + "\n"
                : "Hello,\n\n"
                        + "Confirm your email to finish creating your Flow-Cortex account:\n\n"
                        + link + validity + " If you did not create an account, you can ignore this email.\n";
        mailAfterCommit(userId, email, SUBJECT, body, "sent");
    }

    private void mailPasswordLink(UUID userId, String email) {
        String rawToken = store(userId, EmailLinkPurpose.PASSWORD_LINK, properties.passwordSetupValidity());
        long minutes = properties.passwordSetupValidity().toMinutes();
        mailAfterCommit(userId, email, PASSWORD_LINK_SUBJECT, "Hello,\n\n"
                + "Your Flow-Cortex account has no password yet. Open this link to sign in and choose one:\n\n"
                + publicUrl + "/set-password?token=" + rawToken + "\n\n"
                + "The link works for " + minutes + (minutes == 1 ? " minute" : " minutes") + " and only once. "
                + "If you did not ask for it, you can ignore this email.\n", "password_link_sent");
    }

    /**
     * Retires the account's open links and mails a reset link valid for {@code validity}. The token is
     * in the URL fragment, so no server or proxy log on the way records it. Joins the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailPasswordReset(AppUser locked, Duration validity) {
        int retired = retireOpenLinks(locked.getId());
        LOG.info("email_confirmation outcome=password_reset_link userId={} retiredLinks={}", locked.getId(), retired);
        String rawToken = store(locked.getId(), EmailLinkPurpose.PASSWORD_RESET, validity);
        mailAfterCommit(locked.getId(), locked.getEmail(), PASSWORD_RESET_SUBJECT, "Hello,\n\n"
                + "Someone asked to reset the password of your Flow-Cortex account. Open this link to choose"
                + " a new one:\n\n"
                + publicUrl + "/reset-password#token=" + rawToken + "\n\n"
                + "The link works for " + inWholeUnits(validity) + " and only once. "
                + "If you did not ask for it, you can ignore this email; your password stays as it is.\n",
                "password_reset_sent");
    }

    /** "1 hour", "2 hours" when the validity is whole hours, otherwise "90 minutes". */
    private static String inWholeUnits(Duration validity) {
        long minutes = validity.toMinutes();
        if (minutes >= 60 && minutes % 60 == 0) {
            long hours = minutes / 60;
            return hours + (hours == 1 ? " hour" : " hours");
        }
        return minutes + (minutes == 1 ? " minute" : " minutes");
    }

    /** Tells an account without a password that it signs in with Google, after the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailGoogleSignInNotice(AppUser account) {
        mailAfterCommit(account.getId(), account.getEmail(), GOOGLE_SIGN_IN_SUBJECT, "Hello,\n\n"
                + "Someone asked to reset the password of your Flow-Cortex account. Your account has no"
                + " password: it signs in with Google. Open the sign-in page and continue with Google:\n\n"
                + publicUrl + "/login\n\n"
                + "If you did not ask for this, you can ignore this email.\n", "google_notice_sent");
    }

    /**
     * Whether the token is a reset link that is neither used nor expired, read without locking or
     * spending anything; superseded links count as used. Joins the caller's transaction.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean isOpenPasswordReset(String rawToken) {
        Instant now = Instant.now(clock);
        EmailConfirmationToken probe = new EmailConfirmationToken(null, null, EmailLinkPurpose.PASSWORD_RESET,
                OpaqueTokens.hash(rawToken), null, null);
        return tokens.findOne(Example.of(probe))
                .filter(token -> token.getUsedAt() == null && token.getExpiresAt().isAfter(now))
                .isPresent();
    }

    /**
     * Spends a reset link and returns its account, whose row the caller's transaction now holds locked.
     * Empty when the link is unknown, used, superseded, expired, or its account is gone.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<AppUser> spendPasswordReset(String rawToken) {
        return spendLink(rawToken, EmailLinkPurpose.PASSWORD_RESET, Instant.now(clock));
    }

    /** Tells the account's address its password was changed, after the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailPasswordChangedNotice(AppUser account) {
        mailAfterCommit(account.getId(), account.getEmail(), PASSWORD_CHANGED_SUBJECT, "Hello,\n\n"
                + "The password of your Flow-Cortex account was just changed.\n\n"
                + "We signed you out on every device. A device that was already open may stay signed in for"
                + " up to 15 minutes.\n\n"
                + "If this wasn't you, reset your password again from the sign-in page:\n\n"
                + publicUrl + "/login\n", "password_changed_notice");
    }

    private String store(UUID userId, EmailLinkPurpose purpose, Duration validity) {
        Instant now = Instant.now(clock);
        String rawToken = OpaqueTokens.generateRawToken();
        tokens.save(new EmailConfirmationToken(UUID.randomUUID(), userId, purpose, OpaqueTokens.hash(rawToken), now,
                now.plus(validity)));
        return rawToken;
    }

    private void mailAfterCommit(UUID userId, String email, String subject, String body, String sentOutcome) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailExecutor.execute(() -> send(userId, email, subject, body, sentOutcome));
                } catch (TaskRejectedException ex) {
                    sendFailures.increment();
                    LOG.warn("email_confirmation outcome=send_rejected userId={} mail={} reason=queue_full", userId,
                            sentOutcome);
                }
            }
        });
    }

    private void send(UUID userId, String email, String subject, String body, String sentOutcome) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(subject);
        message.setText(body);
        try {
            mailSender.send(message);
            LOG.info("email_confirmation outcome={} userId={}", sentOutcome, userId);
        } catch (RuntimeException ex) {
            sendFailures.increment();
            // Only the class name: a send exception can carry the failed message, link included.
            LOG.warn("email_confirmation outcome=send_failed userId={} mail={} error={}", userId, sentOutcome,
                    ex.getClass().getName());
        }
    }
}
