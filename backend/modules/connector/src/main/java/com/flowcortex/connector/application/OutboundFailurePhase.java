package com.flowcortex.connector.application;

import java.io.IOException;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.nio.channels.UnresolvedAddressException;
import java.net.http.HttpConnectTimeoutException;
import javax.net.ssl.SSLHandshakeException;

/**
 * How far an outbound request got when it failed. Behind the egress proxy the TCP connect always goes
 * to the proxy, so a refused host or an unreachable upstream arrives as a failed tunnel, not as a
 * {@link ConnectException}; the classifier reads the JDK's recorded failure shapes.
 */
public enum OutboundFailurePhase {

    /** The connection, tunnel or TLS handshake never completed: nothing of the request was written. */
    CONNECT_PHASE,

    /** The request may have been written (read timeout, reset, or a failure that cannot be told apart). */
    AFTER_WRITE;

    private static final String TUNNEL_REFUSED = "Tunnel failed";

    /** Walks the cause chain; any connect-side failure wins, everything unknown is {@link #AFTER_WRITE}. */
    public static OutboundFailurePhase classify(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
            if (connectSide(cause)) {
                return CONNECT_PHASE;
            }
        }
        return AFTER_WRITE;
    }

    private static boolean connectSide(Throwable cause) {
        return cause instanceof ConnectException
                || cause instanceof UnknownHostException
                || cause instanceof UnresolvedAddressException
                || cause instanceof HttpConnectTimeoutException
                || cause instanceof SSLHandshakeException
                || cause instanceof IOException io && io.getMessage() != null
                        && io.getMessage().startsWith(TUNNEL_REFUSED);
    }
}
