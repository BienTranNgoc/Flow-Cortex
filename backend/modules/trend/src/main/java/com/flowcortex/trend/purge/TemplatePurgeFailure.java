package com.flowcortex.trend.purge;

/**
 * Optional on an exception thrown by a purge port: its {@link #code()} is what the purge logs for the
 * failure, so the log never carries the exception message (which can hold an object key). Without it
 * the log carries the exception's simple class name.
 */
public interface TemplatePurgeFailure {

    /** A short, stable, message-free code such as {@code AccessDenied}. */
    String code();
}
