package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.RefundQueryOutcome;
import java.math.BigInteger;
import tools.jackson.databind.JsonNode;

/**
 * Reduces MoMo's refund-query answer to the verdict a caller can act on. {@code Settled} authorises
 * resolving the refund without sending another, so it is produced only when the query and the refund's own
 * entry both report success and state a transaction id and an amount.
 */
final class MomoRefundQuery {

    private static final String STILL_PROCESSING = "1080";
    private static final String SUCCESS = "0";

    private MomoRefundQuery() {
    }

    static RefundQueryOutcome interpret(JsonNode answer, String orderRef) {
        if (answer == null || !answer.isObject()) {
            return new RefundQueryOutcome.Unknown(null);
        }
        String queryCode = text(answer.has("resultCode") ? answer.get("resultCode") : answer.get("errorCode"));
        JsonNode entry = entryFor(answer.get("refundTrans"), orderRef);
        String entryCode = entry == null ? null : text(entry.get("resultCode"));

        if (SUCCESS.equals(queryCode) && SUCCESS.equals(entryCode)) {
            String transactionId = text(entry.get("transId"));
            Long amount = amount(entry.get("amount"));
            if (transactionId != null && amount != null) {
                return new RefundQueryOutcome.Settled(transactionId, amount, SUCCESS);
            }
        }
        if (STILL_PROCESSING.equals(queryCode) || STILL_PROCESSING.equals(entryCode)) {
            return new RefundQueryOutcome.StillPending(queryCode);
        }
        return new RefundQueryOutcome.Unknown(queryCode);
    }

    /** The entry whose order id is ours, else the only entry there is. */
    private static JsonNode entryFor(JsonNode entries, String orderRef) {
        if (entries == null || !entries.isArray()) {
            return null;
        }
        for (JsonNode candidate : entries) {
            if (candidate.isObject() && orderRef.equals(text(candidate.get("orderId")))) {
                return candidate;
            }
        }
        return entries.size() == 1 && entries.get(0).isObject() ? entries.get(0) : null;
    }

    private static String text(JsonNode node) {
        if (node == null) {
            return null;
        }
        if (node.isString()) {
            return node.asString().isEmpty() ? null : node.asString();
        }
        return node.isIntegralNumber() ? node.bigIntegerValue().toString() : null;
    }

    private static Long amount(JsonNode node) {
        if (node == null || !node.isIntegralNumber()) {
            return null;
        }
        BigInteger value = node.bigIntegerValue();
        return value.signum() >= 0 && value.bitLength() < Long.SIZE ? value.longValueExact() : null;
    }
}
