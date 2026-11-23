package com.flowcortex.user.phone;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailOutcome;
import com.flowcortex.mail.api.MailRecipientDirectory;
import com.flowcortex.mail.api.Mailer;
import com.flowcortex.mail.api.OutgoingMail;
import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.shared.web.RequestLocale;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.api.StepUpProofs;
import com.flowcortex.user.api.StepUpPurpose;
import com.flowcortex.user.application.AuditEventWriter;
import com.flowcortex.user.application.AuthEventType;
import com.flowcortex.user.phone.PhoneChallenge.Purpose;
import com.flowcortex.user.phone.PhoneChangeStartPolicy.Refusal;
import com.flowcortex.user.phone.PhoneChangeStartPolicy.StartFacts;
import com.flowcortex.user.phone.PhoneVerificationService.Result;
import com.flowcortex.user.phone.PhoneVerificationService.SentCode;
import com.flowcortex.user.phone.PhoneVerificationService.Verification;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.MessageSource;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Changes a member's verified number: a step-up proof, a code sent to the new number, then a swap
 * that keeps the replaced number for a day so it can be put back. A verify and a cancel run inside
 * the member's phone lock (the one a send takes), so they never interleave and the undo stack
 * always unwinds in the reverse order of the changes that committed. Notices go out after the commit
 * and a failing one is logged, never thrown. Log lines carry the last three digits of a number.
 */
@Service
class PhoneChangeService {

    static final Duration UNDO_WINDOW = Duration.ofHours(24);

    private static final LocalizedMessage CHANGED_NOTICE = new LocalizedMessage("sms.phoneChanged.body");
    private static final LocalizedMessage REVERTED_NOTICE = new LocalizedMessage("sms.phoneChangeReverted.body");
    private static final Logger LOG = LoggerFactory.getLogger(PhoneChangeService.class);
    private static final String START = "start";
    private static final String VERIFY = "verify";
    private static final String CANCEL = "cancel";

    private final StepUpProofs stepUpProofs;
    private final PhoneVerificationService verification;
    private final PhoneChallengeRepository challenges;
    private final UserPhoneRepository phones;
    private final SmsSender smsSender;
    private final Mailer mailer;
    private final MailRecipientDirectory recipients;
    private final TaskExecutor mailExecutor;
    private final AuditEventWriter audit;
    private final PhoneTelemetry telemetry;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final MessageSource messages;
    private final RequestLocale requestLocale;

    PhoneChangeService(StepUpProofs stepUpProofs, PhoneVerificationService verification,
            PhoneChallengeRepository challenges, UserPhoneRepository phones, ObjectProvider<SmsSender> smsSenders,
            Mailer mailer, MailRecipientDirectory recipients,
            @Qualifier("applicationTaskExecutor") TaskExecutor mailExecutor, AuditEventWriter audit,
            PhoneTelemetry telemetry, JdbcTemplate jdbc, PlatformTransactionManager transactionManager,
            MessageSource messages, RequestLocale requestLocale) {
        this.stepUpProofs = stepUpProofs;
        this.verification = verification;
        this.challenges = challenges;
        this.phones = phones;
        this.smsSender = smsSenders.getIfAvailable();
        this.mailer = mailer;
        this.recipients = recipients;
        this.mailExecutor = mailExecutor;
        this.audit = audit;
        this.telemetry = telemetry;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.messages = messages;
        this.requestLocale = requestLocale;
    }

    /** What a swap left behind, or why it was refused; {@code replaced} is set only when it committed. */
    private record Swap(Verification check, String replaced) {
    }

    private enum CancelResult {
        REVERTED,
        NO_UNDO_WINDOW,
        REVERT_CONFLICT,
        REVERT_TAKEN
    }

    private record Cancellation(CancelResult result, String restored, String removed) {
    }

    /**
     * Spends the proof first, so a refusal after it still costs the member a new one, then refuses
     * by {@link PhoneChangeStartPolicy} and sends one change code to {@code rawPhone} under the
     * send quotas.
     *
     * @throws com.flowcortex.user.api.StepUpRequiredException when the proof is missing, spent, expired or not theirs
     */
    SentCode start(UUID userId, String rawPhone, String stepUpProof, String clientIp) {
        String phone = PhoneNumbers.normalize(rawPhone)
                .orElseThrow(() -> new IllegalArgumentException("Not a Vietnamese mobile number"));
        try {
            stepUpProofs.consume(userId, StepUpPurpose.PHONE_CHANGE, stepUpProof);
            Optional<UserPhone> current = phones.findById(userId).filter(UserPhone::isVerified);
            Optional<Refusal> refusal = PhoneChangeStartPolicy.firstRefusal(new StartFacts(current.isPresent(),
                    current.filter(row -> row.getPhoneE164().equals(phone)).isPresent()));
            if (refusal.isPresent()) {
                throw startRefusal(refusal.get());
            }
            SentCode sent = verification.sendChallenge(userId, phone, clientIp, Purpose.CHANGE);
            telemetry.change(START, "sent");
            return sent;
        } catch (RuntimeException refused) {
            telemetry.change(START, outcomeOf(refused));
            LOG.info("Phone change start refused: userId={} phone={} outcome={}", userId, PhoneNumbers.forLog(phone),
                    outcomeOf(refused));
            throw refused;
        }
    }

    /**
     * Checks the member's open change code and, when it is right, swaps the number. A wrong entry is
     * counted in the committed transaction before the refusal is thrown.
     */
    void verify(UUID userId, String code, String clientIp, String userAgent) {
        Instant now = verification.now();
        Swap swap;
        try {
            swap = transaction.execute(status -> swapLocked(userId, code, now));
        } catch (DataIntegrityViolationException e) {
            if (!PhoneVerificationService.isVerifiedNumberTaken(e)) {
                throw e;
            }
            swap = new Swap(new Verification(Result.NUMBER_TAKEN, null, 0), null);
        }
        Verification check = swap.check();
        String outcome = check.result() == Result.VERIFIED ? "changed" : check.result().name().toLowerCase(Locale.ROOT);
        telemetry.change(VERIFY, outcome);
        LOG.info("Phone change code checked: userId={} phone={} outcome={}", userId,
                check.phone() == null ? "none" : PhoneNumbers.forLog(check.phone()), outcome);
        if (check.result() != Result.VERIFIED) {
            throw PhoneVerificationService.refusalOf(check);
        }
        announceChange(userId, swap.replaced(), check.phone(), clientIp, userAgent);
    }

    /** Puts the replaced number back inside the undo window of the member's newest change. */
    void cancel(UUID userId, String clientIp, String userAgent) {
        Instant now = verification.now();
        Cancellation cancellation;
        try {
            cancellation = transaction.execute(status -> cancelLocked(userId, now));
        } catch (DataIntegrityViolationException e) {
            if (!PhoneVerificationService.isVerifiedNumberTaken(e)) {
                throw e;
            }
            cancellation = new Cancellation(CancelResult.REVERT_TAKEN, null, null);
        }
        if (cancellation.result() != CancelResult.REVERTED) {
            RuntimeException refusal = cancelRefusal(cancellation.result());
            telemetry.change(CANCEL, outcomeOf(refusal));
            LOG.info("Phone change undo refused: userId={} outcome={}", userId, outcomeOf(refusal));
            throw refusal;
        }
        telemetry.change(CANCEL, "reverted");
        LOG.info("Phone change undone: userId={} phone={}", userId, PhoneNumbers.forLog(cancellation.restored()));
        announceRevert(userId, cancellation.removed(), cancellation.restored(), clientIp, userAgent);
    }

    private Swap swapLocked(UUID userId, String code, Instant now) {
        lockMember(userId);
        Optional<PhoneChallenge> latest = challenges
                .findFirstByUserIdAndPurposeAndConsumedAtIsNullAndInvalidatedAtIsNullOrderByCreatedAtDesc(userId,
                        Purpose.CHANGE)
                .or(() -> challenges.findFirstByUserIdAndPurposeOrderByCreatedAtDesc(userId, Purpose.CHANGE));
        if (latest.isEmpty()) {
            return refused(Result.NO_PENDING_CHANGE, null);
        }
        PhoneChallenge challenge = latest.get();
        String next = challenge.getPhoneE164();
        Optional<Verification> wrong = verification.refuseUnlessRight(userId, challenge, code, now);
        if (wrong.isPresent()) {
            return new Swap(wrong.get(), null);
        }
        Optional<UserPhone> current = phones.findById(userId).filter(UserPhone::isVerified);
        if (current.isEmpty()) {
            return refused(Result.NOT_VERIFIED, next);
        }
        UserPhone row = current.get();
        String replaced = row.getPhoneE164();
        if (replaced.equals(next)) {
            return refused(Result.SAME_NUMBER, next);
        }
        if (phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(next, userId)) {
            return refused(Result.NUMBER_TAKEN, next);
        }
        if (challenges.consume(challenge.getId(), now) == 0) {
            return refused(verification.lockedOrStale(challenge.getId()), next);
        }
        row.verify(next, now);
        phones.saveAndFlush(row);
        if (challenges.markChanged(challenge.getId(), replaced, now.plus(UNDO_WINDOW)) == 0) {
            throw new IllegalStateException("The consumed change code " + challenge.getId() + " was already marked");
        }
        return new Swap(new Verification(Result.VERIFIED, next, 0), replaced);
    }

    private Cancellation cancelLocked(UUID userId, Instant now) {
        lockMember(userId);
        Optional<PhoneChallenge> latest = challenges.findLatestUndoable(userId, now);
        if (latest.isEmpty()) {
            return new Cancellation(CancelResult.NO_UNDO_WINDOW, null, null);
        }
        PhoneChallenge change = latest.get();
        String removed = change.getPhoneE164();
        String restored = change.getPriorPhoneE164();
        boolean stillCurrent = phones.findById(userId).filter(UserPhone::isVerified)
                .filter(row -> row.getPhoneE164().equals(removed)).isPresent();
        if (!stillCurrent) {
            return new Cancellation(CancelResult.REVERT_CONFLICT, restored, removed);
        }
        if (phones.existsByPhoneE164AndVerifiedAtIsNotNullAndUserIdNot(restored, userId)) {
            return new Cancellation(CancelResult.REVERT_TAKEN, restored, removed);
        }
        if (phones.restoreNumber(userId, removed, restored, now) == 0) {
            return new Cancellation(CancelResult.REVERT_CONFLICT, restored, removed);
        }
        if (challenges.markReverted(change.getId(), now) == 0) {
            throw new IllegalStateException("The change " + change.getId() + " was already reverted");
        }
        return new Cancellation(CancelResult.REVERTED, restored, removed);
    }

    private void lockMember(UUID userId) {
        jdbc.query(PhoneVerificationService.LOCK, (RowCallbackHandler) row -> { },
                PhoneVerificationService.memberLockKey(userId));
    }

    private static Swap refused(Result result, String phone) {
        return new Swap(new Verification(result, phone, 0), null);
    }

    private static RuntimeException startRefusal(Refusal refusal) {
        return switch (refusal) {
            case NOT_VERIFIED -> new PhoneConflictException("PHONE_NOT_VERIFIED",
                    new LocalizedMessage("phone.change.notVerified"));
            case SAME_NUMBER -> new UnprocessableEntityException(new LocalizedMessage("phone.change.sameNumber"),
                    "PHONE_SAME_NUMBER");
        };
    }

    private static RuntimeException cancelRefusal(CancelResult result) {
        return switch (result) {
            case NO_UNDO_WINDOW -> new PhoneConflictException("PHONE_NO_UNDO_WINDOW",
                    new LocalizedMessage("phone.change.noUndoWindow"));
            case REVERT_CONFLICT -> new PhoneConflictException("PHONE_REVERT_CONFLICT",
                    new LocalizedMessage("phone.change.revertConflict"));
            case REVERT_TAKEN -> new PhoneConflictException("PHONE_REVERT_TAKEN",
                    new LocalizedMessage("phone.change.revertTaken"));
            case REVERTED -> throw new IllegalStateException("A reverted change has no refusal");
        };
    }

    /** The refusal's machine code lower-cased, a closed set; anything unexpected is {@code error}. */
    private static String outcomeOf(RuntimeException refused) {
        Object code = null;
        if (refused instanceof LocalizedStatusException status && status.getBody().getProperties() != null) {
            code = status.getBody().getProperties().get("code");
        } else if (refused instanceof UnprocessableEntityException unprocessable) {
            code = unprocessable.getCode();
        } else if (refused instanceof ConflictException) {
            code = "number_taken";
        }
        return code == null ? "error" : code.toString().toLowerCase(Locale.ROOT);
    }

    private void announceChange(UUID userId, String replaced, String added, String clientIp, String userAgent) {
        Locale locale = requestLocale.current();
        audit.write(AuthEventType.PHONE_CHANGED, userId, clientIp, userAgent,
                AuthEventType.metadata("old", PhoneNumbers.mask(replaced), "new", PhoneNumbers.mask(added)));
        textNotice(userId, replaced, CHANGED_NOTICE, locale);
        mailNotice(userId, PhoneNumbers.mask(added), locale);
    }

    private void announceRevert(UUID userId, String removed, String restored, String clientIp, String userAgent) {
        Locale locale = requestLocale.current();
        audit.write(AuthEventType.PHONE_CHANGE_REVERTED, userId, clientIp, userAgent,
                AuthEventType.metadata("old", PhoneNumbers.mask(removed), "new", PhoneNumbers.mask(restored)));
        textNotice(userId, removed, REVERTED_NOTICE, locale);
        textNotice(userId, restored, REVERTED_NOTICE, locale);
    }

    private void textNotice(UUID userId, String phone, LocalizedMessage notice, Locale locale) {
        try {
            if (smsSender == null) {
                throw new IllegalStateException("No SMS sender is configured (flowcortex.sms.sender)");
            }
            smsSender.send(phone, messages.getMessage(notice, locale));
        } catch (RuntimeException failure) {
            LOG.warn("Phone change notice not sent: userId={} phone={} channel=sms error={}", userId,
                    PhoneNumbers.forLog(phone), failure.getClass().getName());
        }
    }

    /** The address is read here, on the request's thread; delivery runs on the mail executor. */
    private void mailNotice(UUID userId, String maskedNew, Locale locale) {
        try {
            Optional<String> email = recipients.emailOf(userId);
            if (email.isEmpty()) {
                LOG.warn("Phone change notice not sent: userId={} channel=mail error=no_address", userId);
                return;
            }
            OutgoingMail mail = new OutgoingMail(MailKey.PHONE_CHANGED, userId, email.get(), locale,
                    List.of(maskedNew));
            mailExecutor.execute(() -> deliver(mail));
        } catch (TaskRejectedException rejected) {
            LOG.warn("Phone change notice not sent: userId={} channel=mail error=queue_full", userId);
        } catch (RuntimeException failure) {
            LOG.warn("Phone change notice not sent: userId={} channel=mail error={}", userId,
                    failure.getClass().getName());
        }
    }

    private void deliver(OutgoingMail mail) {
        try {
            if (mailer.send(mail) == MailOutcome.FAILED) {
                LOG.warn("Phone change notice not sent: userId={} channel=mail error=transport_failed",
                        mail.memberId());
            }
        } catch (RuntimeException failure) {
            LOG.warn("Phone change notice not sent: userId={} channel=mail error={}", mail.memberId(),
                    failure.getClass().getName());
        }
    }
}
