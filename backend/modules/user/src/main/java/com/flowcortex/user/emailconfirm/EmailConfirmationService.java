package com.flowcortex.user.emailconfirm;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Issues, mails and spends the account's one-time tokens ({@link EmailLinkPurpose}): confirmation
 * links, set-password links and password-setup tokens. A token carries 32 random bytes; only their
 * SHA-256 hex is stored, and neither the token nor a link is ever logged.
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

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final EmailConfirmationTokenRepository tokens;
    private final AppUserRepository users;
    private final JavaMailSender mailSender;
    private final TaskExecutor mailExecutor;
    private final EmailConfirmationProperties properties;
    private final Clock clock;
    private final String publicUrl;
    private final Counter sendFailures;
    private final MessageSource messages;
    private final RequestLocale requestLocale;

    /** Fails startup when confirmation is on and a link could not be built or mailed. */
    EmailConfirmationService(EmailConfirmationTokenRepository tokens, AppUserRepository users,
            JavaMailSender mailSender, @Qualifier("applicationTaskExecutor") TaskExecutor mailExecutor,
            EmailConfirmationProperties properties, Clock clock,
            @Value("${flowcortex.app.public-url:}") String publicUrl, @Value("${spring.mail.host:}") String mailHost,
            MeterRegistry meters, MessageSource messages, RequestLocale requestLocale) {
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
                .description("Account mails (confirmation, set-password link, password notice) the SMTP server"
                        + " did not accept, or the send queue had no room for")
                .register(meters);
        this.messages = messages;
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
        mailConfirmationLink(userId, email);
    }

    /** Retires the account's open links and issues a new one, in the caller's transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reissue(UUID userId, String email) {
        int retired = retireOpenLinks(userId);
        LOG.info("email_confirmation outcome=reissue userId={} retiredLinks={}", userId, retired);
        mailConfirmationLink(userId, email);
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
        String tokenHash = sha256(rawToken);
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
        return tokens.consume(sha256(rawToken), EmailLinkPurpose.PASSWORD_SETUP, userId, Instant.now(clock)) == 1;
    }

    /** Tells the account's address that a password was set, after the caller's transaction commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void mailPasswordSetNotice(UUID userId, String email) {
        mailAfterCommit(userId, email, new LocalizedMessage("email.passwordSet.subject"),
                new LocalizedMessage("email.passwordSet.body"), "password_set_notice");
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
        AppUser user = found.get();
        int retired = retireOpenLinks(user.getId());
        if (user.isEmailConfirmed()) {
            LOG.info("email_confirmation outcome=password_link userId={} retiredLinks={}", user.getId(), retired);
            mailPasswordLink(user.getId(), user.getEmail());
        } else {
            LOG.info("email_confirmation outcome=resend userId={} retiredLinks={}", user.getId(), retired);
            mailConfirmationLink(user.getId(), user.getEmail());
        }
    }

    private void mailConfirmationLink(UUID userId, String email) {
        String rawToken = store(userId, EmailLinkPurpose.EMAIL_CONFIRM, properties.validity());
        mailAfterCommit(userId, email, new LocalizedMessage("email.confirm.subject"),
                new LocalizedMessage("email.confirm.body", publicUrl + "/confirm-email?token=" + rawToken,
                        properties.validity().toHours()), "sent");
    }

    private void mailPasswordLink(UUID userId, String email) {
        String rawToken = store(userId, EmailLinkPurpose.PASSWORD_LINK, properties.passwordSetupValidity());
        mailAfterCommit(userId, email, new LocalizedMessage("email.passwordLink.subject"),
                new LocalizedMessage("email.passwordLink.body", publicUrl + "/set-password?token=" + rawToken,
                        properties.passwordSetupValidity().toMinutes()), "password_link_sent");
    }

    private String store(UUID userId, EmailLinkPurpose purpose, Duration validity) {
        Instant now = Instant.now(clock);
        String rawToken = newRawToken();
        tokens.save(new EmailConfirmationToken(UUID.randomUUID(), userId, purpose, sha256(rawToken), now,
                now.plus(validity)));
        return rawToken;
    }

    /**
     * The text is resolved here, on the request's thread: the mail executor runs after the request has
     * ended, when its language can no longer be read.
     */
    private void mailAfterCommit(UUID userId, String email, LocalizedMessage subjectKey, LocalizedMessage bodyKey,
            String sentOutcome) {
        Locale locale = requestLocale.current();
        String subject = messages.getMessage(subjectKey, locale);
        String body = messages.getMessage(bodyKey, locale);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    mailExecutor.execute(() -> send(userId, email, subject, body, sentOutcome));
                } catch (TaskRejectedException ex) {
                    sendFailures.increment();
                    LOG.warn("email_confirmation outcome=send_rejected userId={} reason=queue_full", userId);
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
            LOG.warn("email_confirmation outcome=send_failed userId={} error={}", userId, ex.getClass().getName());
        }
    }

    private static String newRawToken() {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return URL_ENCODER.encodeToString(bytes);
    }

    private static String sha256(String rawToken) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(rawToken.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }
}
