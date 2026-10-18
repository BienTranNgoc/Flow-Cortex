package com.flowcortex.credit.application;

import java.util.Set;

/**
 * Which step failures give the member their credits back. The user-fault codes mean the member's
 * request or file was unusable; every other code, a missing one included, is the system's fault.
 */
final class RefundPolicy {

    static final Set<String> USER_FAULT = Set.of("INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected",
            "probe_failed");

    private RefundPolicy() {
    }

    static boolean refunds(String errorCode) {
        return errorCode == null || !USER_FAULT.contains(errorCode);
    }
}
