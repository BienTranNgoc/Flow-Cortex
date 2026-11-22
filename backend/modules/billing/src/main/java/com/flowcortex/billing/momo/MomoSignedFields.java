package com.flowcortex.billing.momo;

import java.util.List;

/**
 * What MoMo signs, per endpoint and for the one-time payment callback, in MoMo's order. MoMo signs a fixed
 * list of fields rather than the body; a field in a list that is absent from the payload cannot be skipped,
 * and a body field outside a list is never signed.
 */
public final class MomoSignedFields {

    /** {@code /create} for every one-time request type. */
    public static final List<String> CREATE = List.of("accessKey", "amount", "extraData", "ipnUrl", "orderId",
            "orderInfo", "partnerCode", "redirectUrl", "requestId", "requestType");

    public static final List<String> QUERY = List.of("accessKey", "orderId", "partnerCode", "requestId");

    public static final List<String> REFUND = List.of("accessKey", "amount", "description", "orderId",
            "partnerCode", "requestId", "transId");

    /** A refund is asked about by the refund's own order id; the list is the query list. */
    public static final List<String> REFUND_QUERY = QUERY;

    /** The one-time payment callback (IPN). */
    public static final List<String> IPN_ONE_TIME = List.of("accessKey", "amount", "extraData", "message",
            "orderId", "orderInfo", "orderType", "partnerCode", "payType", "requestId", "responseTime",
            "resultCode", "transId");

    public static final String CREATE_PATH = "/v2/gateway/api/create";
    public static final String QUERY_PATH = "/v2/gateway/api/query";
    public static final String REFUND_PATH = "/v2/gateway/api/refund";
    public static final String REFUND_QUERY_PATH = "/v2/gateway/api/refund/query";

    private MomoSignedFields() {
    }
}
