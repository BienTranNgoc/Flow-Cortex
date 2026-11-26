package com.flowcortex.billing.refund;

import com.flowcortex.billing.refund.RefundResponses.RightView;
import com.flowcortex.billing.refund.RefundResponses.TurnsView;

/**
 * The payment's change-of-mind verdict is a refusal: 409 with code {@value #CODE}, the refusal, and for a turn
 * limit the limit, its window and the next opening ({@link RefundProblemHandler}).
 */
public class RefundNotEligibleException extends RuntimeException {

    static final String CODE = "refund_not_eligible";

    private final transient RightView right;
    private final transient TurnsView turns;

    RefundNotEligibleException(RightView right, TurnsView turns) {
        super(CODE + ": " + right.refusal());
        this.right = right;
        this.turns = turns;
    }

    public RightView right() {
        return right;
    }

    public TurnsView turns() {
        return turns;
    }
}
