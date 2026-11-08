package com.flowcortex.workflow.application.provider;

/**
 * The provider did not answer a request after every attempt on every model it may use. The message
 * names only the last model tried — never the request, the provider's answer or the key.
 */
public class GeminiException extends RuntimeException {

    public enum Reason {
        QUOTA_EXCEEDED("provider_quota_exceeded"),
        UNAVAILABLE("provider_unavailable");

        private final String code;

        Reason(String code) {
            this.code = code;
        }
    }

    private final Reason reason;

    public GeminiException(Reason reason, String lastModel) {
        super("Provider gave up on " + lastModel + ": " + reason.code);
        this.reason = reason;
    }

    /** The step error code this refusal ends the step with. */
    public String code() {
        return reason.code;
    }
}
