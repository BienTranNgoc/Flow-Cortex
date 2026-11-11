package com.flowcortex.billing.momo;

import com.flowcortex.billing.provider.PaymentInstrument;
import com.flowcortex.billing.webhook.CallbackSourceAllowlist;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Everything that must hold before MoMo is switched on. Each problem names its property and never a value,
 * except the callback path and an unreadable allowlist entry, which are not secrets.
 */
final class MomoStartupCheck {

    private static final String PREFIX = "flowcortex.billing.momo.";

    private MomoStartupCheck() {
    }

    static List<String> problems(MomoProperties properties, boolean lookupPresent) {
        List<String> problems = new ArrayList<>();
        required(problems, "partner-code", properties.partnerCode());
        required(problems, "access-key", properties.accessKey());
        required(problems, "secret-key", properties.secretKey());
        absoluteHttpsUrl(problems, "base-url", properties.baseUrl());
        absoluteHttpsUrl(problems, "redirect-url", properties.redirectUrl());
        callbackUrl(problems, properties.ipnUrl());
        instruments(problems, properties.offeredInstruments());
        allowlist(problems, properties.callbackSourceAllowlist());
        if (properties.circuit().failureThreshold() < 1) {
            problems.add(PREFIX + "circuit.failure-threshold must be at least 1");
        }
        if (properties.circuit().openDuration().isZero() || properties.circuit().openDuration().isNegative()) {
            problems.add(PREFIX + "circuit.open-duration must be positive");
        }
        if (!lookupPresent) {
            problems.add("no PaymentOrderLookup bean is provided, so callbacks could not be cross-checked against "
                    + "the recorded order; MoMo cannot be enabled without one");
        }
        return problems;
    }

    private static void required(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(PREFIX + name + " is required when MoMo is enabled");
        }
    }

    /** Reports a missing or unusable URL by property name; returns the URI only when it is absolute https. */
    private static URI absoluteHttpsUrl(List<String> problems, String name, String value) {
        if (value == null || value.isBlank()) {
            problems.add(PREFIX + name + " is required when MoMo is enabled");
            return null;
        }
        URI uri;
        try {
            uri = URI.create(value.trim());
        } catch (IllegalArgumentException e) {
            problems.add(PREFIX + name + " is not a valid URL");
            return null;
        }
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getHost().isBlank()) {
            problems.add(PREFIX + name + " must be an absolute https URL with a host");
            return null;
        }
        return uri;
    }

    private static void callbackUrl(List<String> problems, String value) {
        URI uri = absoluteHttpsUrl(problems, "ipn-url", value);
        if (uri != null && !MomoCallbackRoute.PATH.equals(uri.getPath())) {
            problems.add(PREFIX + "ipn-url path is '" + uri.getPath() + "' but the receiver route is "
                    + MomoCallbackRoute.PATH);
        }
    }

    private static void instruments(List<String> problems, List<String> offered) {
        if (offered.isEmpty()) {
            problems.add(PREFIX + "offered-instruments must name at least one instrument");
            return;
        }
        if (offered.stream().anyMatch(wire -> PaymentInstrument.fromWire(wire.trim()).isEmpty())) {
            problems.add(PREFIX + "offered-instruments names an unknown instrument; known: wallet, domestic_card");
        }
    }

    private static void allowlist(List<String> problems, String text) {
        CallbackSourceAllowlist.Result result = CallbackSourceAllowlist.parse(text);
        for (String entry : result.unreadable()) {
            problems.add(PREFIX + "callback-source-allowlist has an unreadable entry: '" + entry + "'");
        }
        if (result.entries().isEmpty() && result.unreadable().isEmpty()) {
            problems.add(PREFIX + "callback-source-allowlist must list the addresses MoMo calls back from");
        }
    }
}
