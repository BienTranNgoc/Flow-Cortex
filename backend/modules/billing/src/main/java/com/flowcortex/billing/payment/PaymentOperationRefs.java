package com.flowcortex.billing.payment;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The two reference shapes a payment carries: our order reference ({@code fc_} and 32 lowercase hex
 * digits) and the request reference sent to a provider (a canonical UUID).
 */
public final class PaymentOperationRefs {

    /** The one UUID check. Both cases are spelled out because a JSON Schema {@code pattern} takes no flags. */
    public static final String UUID_PATTERN =
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

    public static final String ORDER_REF_PATTERN = "^fc_[0-9a-f]{32}$";

    public static final int ORDER_REF_MAX_BYTES = 64;

    private static final Pattern ORDER_REF = Pattern.compile(ORDER_REF_PATTERN);
    private static final SecureRandom RANDOM = new SecureRandom();

    private PaymentOperationRefs() {
    }

    public static String newOrderRef() {
        byte[] bytes = new byte[16];
        RANDOM.nextBytes(bytes);
        return "fc_" + HexFormat.of().formatHex(bytes);
    }

    public static String newRequestRef() {
        return UUID.randomUUID().toString();
    }

    /** Whether the value is an order reference of ours: within the byte ceiling and of the minted shape. */
    public static boolean isOrderRef(String value) {
        return value != null
                && value.getBytes(StandardCharsets.UTF_8).length <= ORDER_REF_MAX_BYTES
                && ORDER_REF.matcher(value).matches();
    }
}
