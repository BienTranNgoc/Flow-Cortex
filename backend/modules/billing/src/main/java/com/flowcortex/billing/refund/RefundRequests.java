package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.ChangeOfMind.Facts;
import com.flowcortex.billing.refund.ChangeOfMind.Right;
import com.flowcortex.billing.refund.RefundRequestRepository.PaymentRow;
import com.flowcortex.billing.refund.RefundRequestRepository.RequestFlags;
import com.flowcortex.billing.refund.RefundRequestRepository.RequestRow;
import com.flowcortex.billing.refund.RefundResponses.ChangeOfMindResponse;
import com.flowcortex.billing.refund.RefundResponses.ChangeOfMindRightView;
import com.flowcortex.billing.refund.RefundResponses.LatestRefundRequestResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundEligibilityResponse;
import com.flowcortex.billing.refund.RefundResponses.RefundRequestView;
import com.flowcortex.billing.refund.RefundResponses.RightView;
import com.flowcortex.billing.refund.RefundResponses.TurnsView;
import com.flowcortex.settings.api.Settings;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The member side of the change-of-mind refund: the verdict over a payment, and requesting and cancelling a
 * request. Every method is scoped to the caller: a payment of another member is
 * {@link RefundTransactionNotFoundException}. The overloads taking an instant judge at that instant.
 */
@Service
public class RefundRequests {

    private static final String DEFAULT_REASON = "Khách sử dụng quyền đổi ý trong những ngày đầu sau khi thanh toán.";

    private final RefundRequestRepository requests;
    private final Settings settings;
    private final RefundTelemetry telemetry;
    private final Clock clock;

    RefundRequests(RefundRequestRepository requests, Settings settings, RefundTelemetry telemetry, Clock clock) {
        this.requests = requests;
        this.settings = settings;
        this.telemetry = telemetry;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public RefundEligibilityResponse eligibility(UUID member, UUID transactionId) {
        return eligibility(member, transactionId, clock.instant());
    }

    @Transactional(readOnly = true)
    public RefundEligibilityResponse eligibility(UUID member, UUID transactionId, Instant now) {
        PaymentRow payment = requests.findPayment(member, transactionId)
                .orElseThrow(RefundTransactionNotFoundException::new);
        TurnStanding standing = standing(member, now);
        Right right = judge(payment, requests.flags(member, transactionId), standing, now);
        Optional<RequestRow> latest = requests.latest(member, transactionId);
        Integer daysElapsed = payment.paidAt() == null ? null
                : (int) Math.max(0, Duration.between(payment.paidAt(), now).toDays());
        return new RefundEligibilityResponse(RightView.of(right), TurnsView.of(standing), payment.paidAt(),
                daysElapsed, latest.map(RefundRequests::view).orElse(null),
                latest.filter(row -> "pending".equals(row.status())).isPresent());
    }

    @Transactional(readOnly = true)
    public ChangeOfMindResponse changeOfMind(UUID member) {
        return changeOfMind(member, clock.instant());
    }

    @Transactional(readOnly = true)
    public ChangeOfMindResponse changeOfMind(UUID member, Instant now) {
        if (!settings.get(RefundSettings.SURFACE_ENABLED).effective()) {
            return new ChangeOfMindResponse(false, false, null, null);
        }
        boolean available = settings.get(RefundSettings.REFUND_ENABLED).effective();
        TurnStanding standing = standing(member, now);
        ChangeOfMindRightView right = requests.latestPlanPurchase(member)
                .map(payment -> ChangeOfMindRightView.of(
                        RightView.of(judge(payment, requests.flags(member, payment.id()), standing, now)),
                        requests.latest(member, payment.id()).map(RefundRequests::view).orElse(null)))
                .orElse(null);
        return new ChangeOfMindResponse(true, available, right, TurnsView.of(standing));
    }

    @Transactional(readOnly = true)
    public LatestRefundRequestResponse latest(UUID member, UUID transactionId) {
        requests.findPayment(member, transactionId).orElseThrow(RefundTransactionNotFoundException::new);
        return new LatestRefundRequestResponse(requests.latest(member, transactionId).map(RefundRequests::view)
                .orElse(null));
    }

    /**
     * Records a pending request when the right is permitted, else answers the payment's pending request.
     * The member lock is taken first; under it the pending request is read before the turns are recounted, so a
     * second press answers the first one's request even when one turn is left, and the insert absorbs a
     * conflict instead of failing.
     */
    @Transactional
    public RefundRequestView request(UUID member, UUID transactionId, String reason) {
        return request(member, transactionId, reason, clock.instant());
    }

    @Transactional
    public RefundRequestView request(UUID member, UUID transactionId, String reason, Instant now) {
        requests.findPayment(member, transactionId).orElseThrow(RefundTransactionNotFoundException::new);
        requests.lockMember(member);
        Optional<RequestRow> pending = requests.pending(member, transactionId);
        if (pending.isPresent()) {
            telemetry.existing();
            return view(pending.get());
        }
        PaymentRow payment = requests.findPayment(member, transactionId)
                .orElseThrow(RefundTransactionNotFoundException::new);
        TurnStanding standing = standing(member, now);
        Right right = judge(payment, requests.flags(member, transactionId), standing, now);
        if (right.refusal() != null) {
            telemetry.refused(right.refusal().value());
            throw new RefundNotEligibleException(RightView.of(right), TurnsView.of(standing));
        }
        Optional<RequestRow> inserted = requests.insertPending(UUID.randomUUID(), member, transactionId,
                storedReason(reason), now);
        if (inserted.isEmpty()) {
            telemetry.existing();
            return view(requests.pending(member, transactionId).orElseThrow(IllegalStateException::new));
        }
        telemetry.created(inserted.get().id(), inserted.get().code());
        return view(inserted.get());
    }

    @Transactional
    public RefundRequestView cancel(UUID member, UUID transactionId) {
        return cancel(member, transactionId, clock.instant());
    }

    /**
     * Cancels the payment's pending request. The payment row is locked so the move is ordered against anything
     * else that decides a request on it; the conditional update is what makes the cancel atomic.
     */
    @Transactional
    public RefundRequestView cancel(UUID member, UUID transactionId, Instant now) {
        requests.lockPayment(member, transactionId).orElseThrow(RefundTransactionNotFoundException::new);
        Optional<RequestRow> cancelled = requests.cancelPending(member, transactionId, now);
        if (cancelled.isEmpty()) {
            telemetry.cancelRefused();
            throw new RefundRequestNotCancellableException();
        }
        telemetry.cancelled(cancelled.get().id(), cancelled.get().code());
        return view(cancelled.get());
    }

    private TurnStanding standing(UUID member, Instant now) {
        boolean enabled = settings.get(RefundSettings.LIMIT_ENABLED).effective();
        int limit = settings.get(RefundSettings.LIMIT_USES).effective();
        int windowMonths = settings.get(RefundSettings.LIMIT_WINDOW_MONTHS).effective();
        var counted = TurnStanding.counted(
                requests.countableSince(member, TurnStanding.windowStart(now, windowMonths)), now, windowMonths);
        return TurnStanding.of(counted, now, limit, windowMonths, enabled);
    }

    private static Right judge(PaymentRow payment, RequestFlags flags, TurnStanding standing, Instant now) {
        return ChangeOfMind.judge(new Facts(payment.id(), payment.amountVnd(), payment.currency(), payment.kind(),
                payment.status(), payment.paidAt(), payment.itemRef(), flags.rejected(), flags.approved(), standing,
                now));
    }

    private static String storedReason(String reason) {
        String trimmed = reason == null ? "" : reason.strip();
        return trimmed.isEmpty() ? DEFAULT_REASON : trimmed;
    }

    private static RefundRequestView view(RequestRow row) {
        boolean processing = "approved".equals(row.status()) || "auto_approved".equals(row.status());
        boolean rejected = "rejected".equals(row.status());
        return new RefundRequestView(row.id(), row.code(), row.status(), row.reason(), row.createdAt(),
                row.decidedAt(), row.decisionNote(), processing ? row.decidedAt() : null,
                rejected ? row.decidedAt() : null, rejected ? row.decisionNote() : null);
    }
}
