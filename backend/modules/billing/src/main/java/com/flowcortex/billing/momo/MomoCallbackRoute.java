package com.flowcortex.billing.momo;

/** Where MoMo posts payment callbacks; the configured callback URL must end in this path. */
public final class MomoCallbackRoute {

    public static final String PATH = "/api/v1/billing/webhooks/momo";

    private MomoCallbackRoute() {
    }
}
