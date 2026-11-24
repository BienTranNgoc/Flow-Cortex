package com.flowcortex.user.apicall;

/** The exception a token write ended with, handed from {@link ApiTokenCallExceptionRecorder} to the filter. */
final class PendingCall {

    static final String ATTRIBUTE = PendingCall.class.getName();

    private Throwable exception;

    Throwable exception() {
        return exception;
    }

    void exception(Throwable exception) {
        this.exception = exception;
    }
}
