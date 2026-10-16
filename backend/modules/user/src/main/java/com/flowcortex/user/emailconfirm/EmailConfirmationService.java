package com.flowcortex.user.emailconfirm;

import com.flowcortex.user.domain.AppUser;
import com.flowcortex.user.domain.UserStatus;
import com.flowcortex.user.infrastructure.AppUserRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Issues, mails and spends email confirmation links. A link carries 32 random bytes; only their
 * SHA-256 hex is stored, and neither the token nor the link is ever logged.
 *
 * <p>The mail leaves after the surrounding transaction commits, so a rolled-back registration
 * sends nothing, and a send failure is logged without failing the request — the member can ask
 * for a new link. A transaction synchronization is used rather than an after-commit event
 * listener because the event publication registry would persist the event, raw token included.
 */
@Service
public class EmailConfirmationService {

    private static final Logger LOG = LoggerFactory.getLogger(EmailConfirmationService.class);

    static final String SUBJECT = "Confirm your Flow-Cortex email";

    private static final SecureRandom RANDOM = new SecureRandom();

    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();

    private final EmailConfirmationTokenRepository tokens;
    private final AppUserRepository users;
    private final JavaMailSender mailSender;
    private final EmailConfirmationProperties properties;
    private final Clock clock;
    private final String publicUrl;

    EmailConfirmationService(EmailConfirmationTokenRepository tokens, AppUserRepository users,
            JavaMailSender mailSender, EmailConfirmationProperties properties, Clock clock,
            @Value("${flowcortex.app.public-url}") String publicUrl) {
        this.tokens = tokens;
        this.users = users;
        this.mailSender = mailSender;
        this.properties = properties;
        this.clock = clock;
        this.publicUrl = publicUrl.endsWith("/") ? publicUrl.substring(0, publicUrl.length() - 1) : publicUrl;
    }

    /** Joins the caller's transaction, so the link is stored only if the account it confirms commits. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void issue(UUID userId, String email) {
        issueInCurrentTransaction(userId, email);
    }

    /**
     * Spends the link and confirms its account in one transaction. Empty when the link is unknown,
     * already used, superseded by a newer one, or past its validity.
     */
    @Transactional
    public Optional<AppUser> confirm(String rawToken) {
        String tokenHash = sha256(rawToken);
        Instant now = Instant.now(clock);
        if (tokens.consume(tokenHash, now) == 0) {
            LOG.info("email_confirmation outcome=link_invalid");
            return Optional.empty();
        }
        Optional<AppUser> user = tokens.findUserIdByTokenHash(tokenHash).flatMap(users::findById);
        user.ifPresent(account -> {
            account.confirmEmail(now);
            LOG.info("email_confirmation outcome=confirmed userId={}", account.getId());
        });
        return user;
    }

    /**
     * Retires the account's open links and mails a new one, when the email belongs to an active
     * account that is still unconfirmed; otherwise does nothing, and the caller answers the same.
     */
    @Transactional
    public void resend(String email) {
        Optional<AppUser> found = users.findByEmail(email).flatMap(user -> users.findByIdForUpdate(user.getId()));
        String skipped = found.isEmpty() ? "unknown_email"
                : found.get().isEmailConfirmed() ? "already_confirmed"
                : found.get().getStatus() != UserStatus.ACTIVE ? "inactive"
                : null;
        if (skipped != null) {
            LOG.info("email_confirmation outcome=resend_skipped reason={}", skipped);
            return;
        }
        AppUser user = found.get();
        int retired = tokens.invalidateOpenFor(user.getId(), Instant.now(clock));
        LOG.info("email_confirmation outcome=resend userId={} retiredLinks={}", user.getId(), retired);
        issueInCurrentTransaction(user.getId(), user.getEmail());
    }

    private void issueInCurrentTransaction(UUID userId, String email) {
        Instant now = Instant.now(clock);
        String rawToken = newRawToken();
        tokens.save(new EmailConfirmationToken(UUID.randomUUID(), userId, sha256(rawToken), now,
                now.plus(properties.validity())));
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                send(userId, email, rawToken);
            }
        });
    }

    private void send(UUID userId, String email, String rawToken) {
        SimpleMailMessage message = new SimpleMailMessage();
        message.setFrom(properties.from());
        message.setTo(email);
        message.setSubject(SUBJECT);
        message.setText(body(publicUrl + "/confirm-email?token=" + rawToken));
        try {
            mailSender.send(message);
            LOG.info("email_confirmation outcome=sent userId={}", userId);
        } catch (RuntimeException ex) {
            // Only the class name: a send exception can carry the failed message, link included.
            LOG.warn("email_confirmation outcome=send_failed userId={} error={}", userId, ex.getClass().getName());
        }
    }

    private String body(String link) {
        long hours = properties.validity().toHours();
        return "Hello,\n\n"
                + "Confirm your email to finish creating your Flow-Cortex account:\n\n"
                + link + "\n\n"
                + "The link works for " + hours + (hours == 1 ? " hour" : " hours") + " and only once. "
                + "If you did not create an account, you can ignore this email.\n";
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
