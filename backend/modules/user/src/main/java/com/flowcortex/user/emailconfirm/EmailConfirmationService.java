package com.flowcortex.user.emailconfirm;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.user.application.OpaqueTokens;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.data.domain.Example;
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

    /** Why a link is reissued; the confirmation mail's wording depends on it. */
    public enum LinkRequest {
        RESEND,
        PASSWORD_RESET
    }

    private final EmailConfirmationTokenRepository tokens;
    private final AppUserRepository users;
    private final Mailer mailer;
    private final TaskExecutor mailExecutor;
    private final EmailConfirmationProperties properties;
    private final Clock clock;
    private final String publicUrl;
    private final Counter sendFailures;
    private final RequestLocale requestLocale;

    /** Fails startup when confirmation is on and a link could not be built or mailed. */
    EmailConfirmationService(EmailConfirmationTokenRepository tokens, AppUserRepository users,
            Mailer mailer, @Qualifier("applicationTaskExecutor") TaskExecutor mailExecutor,
            EmailConfirmationProperties properties, Clock clock,
            @Value("${flowcortex.app.public-url:}") String publicUrl, @Value("${spring.mail.host:}") String mailHost,
            MeterRegistry meters, RequestLocale requestLocale) {
        if (properties.enabled()) {
            requireSet(publicUrl, "flowcortex.app.public-url (FLOWCORTEX_APP_PUBLIC_URL), the member app origin"
                    + " the confirmation link opens");
            requireSet(mailHost, "spring.mail.host (SPRING_MAIL_HOST), the SMTP server confirmation mail goes to");
        }
        this.tokens = tokens;
        this.users = users;
        this.mailer = mailer;
        this.mailExecutor = mailExecutor;
        this.properties = properties;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
        this.sendFailures = Counter.builder("email.confirmation.send_failed")
                .description("Account mails (confirmation, set-password and reset links, notices) the SMTP server"
                        + " did not accept, or the send queue had no room for")
                .register(meters);
        this.requestLocale = requestLocale;
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
        mailAfterCommit(userId, email, MailKey.PASSWORD_SET);
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
        // Asked for by a reset, so "ignore this email" would be wrong advice.
        String link = publicUrl + "/confirm-email?token=" + rawToken;
        MailKey key = request == LinkRequest.PASSWORD_RESET ? MailKey.ACTIVATION_AFTER_RESET_REQUEST
                : MailKey.ACTIVATION;
        mailAfterCommit(userId, email, key, link, properties.validity().toHours());
    }

    private void mailPasswordLink(UUID userId, String email) {
        String rawToken = store(userId, EmailLinkPurpose.PASSWORD_LINK, properties.passwordSetupValidity());
        mailAfterCommit(userId, email, MailKey.PASSWORD_SETUP_LINK, publicUrl + "/set-password?token=" + rawToken,
                properties.passwordSetupValidity().toMinutes());
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
        mailAfterCommit(locked.getId(), locked.getEmail(), MailKey.RESET_PASSWORD,
                publicUrl + "/reset-password#token=" + rawToken, inWholeUnits(validity));
    }

    /** "1 hour", "2 hours" when the validity is whole hours, otherwise "90 minutes"; resolved in the mail's language. */
    private static LocalizedMessage inWholeUnits(Duration validity) {
        long minutes = validity.toMinutes();
        return minutes >= 60 && minutes % 60 == 0
                ? new LocalizedMessage("email.duration.hours", minutes / 60)
                : new LocalizedMessage("email.duration.minutes", minutes);
    }

    /** Tells an account without a password that it signs in with Google, after the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailGoogleSignInNotice(AppUser account) {
        mailAfterCommit(account.getId(), account.getEmail(), MailKey.GOOGLE_SIGN_IN_NOTICE, publicUrl + "/login");
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
        mailAfterCommit(account.getId(), account.getEmail(), MailKey.PASSWORD_CHANGED, publicUrl + "/login");
    }

    private String store(UUID userId, EmailLinkPurpose purpose, Duration validity) {
        Instant now = Instant.now(clock);
        String rawToken = OpaqueTokens.generateRawToken();
        tokens.save(new EmailConfirmationToken(UUID.randomUUID(), userId, purpose, OpaqueTokens.hash(rawToken), now,
                now.plus(validity)));
        return rawToken;
    }

    /**
     * Only the language is captured here, on the request's thread: the mail executor runs after the request
     * has ended, when it can no longer be read. A wrong value count is a coding error and fails the
     * request before anything is registered, rather than surfacing later on the executor.
     */
    private void mailAfterCommit(UUID userId, String email, MailKey key, Object... values) {
        if (values.length != key.valueCount()) {
            throw new IllegalArgumentException("Mail " + key.id() + " takes " + key.valueCount() + " values, got "
                    + values.length);
        }
        OutgoingMail mail = new OutgoingMail(key, userId, email, requestLocale.current(), List.of(values));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailExecutor.execute(() -> send(mail));
                } catch (TaskRejectedException ex) {
                    sendFailures.increment();
                    LOG.warn("email_confirmation outcome=send_rejected userId={} mail={} reason=queue_full",
                            mail.memberId(), mail.key().id());
                }
            }
        });
    }

    private void send(OutgoingMail mail) {
        try {
            if (mailer.send(mail) == MailOutcome.FAILED) {
                sendFailures.increment();
            }
        } catch (IllegalArgumentException ex) {
            sendFailures.increment();
            LOG.warn("email_confirmation outcome=send_failed userId={} mail={} error={}", mail.memberId(),
                    mail.key().id(), ex.getClass().getName());
        }
    }
}
