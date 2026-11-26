package com.flowcortex.billing.ledger;

import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.transaction.CannotCreateTransactionException;

/** Tells a failure caused by an unreachable database from one caused by the work itself. */
public final class ConnectionFailure {

    private static final int MAX_CAUSE_DEPTH = 10;

    private ConnectionFailure() {
    }

    /** Whether {@code failure} or one of its causes says the database could not be reached. */
    public static boolean isConnectionClass(Throwable failure) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < MAX_CAUSE_DEPTH; depth++, cause = cause.getCause()) {
            if (cause instanceof DataAccessResourceFailureException
                    || cause instanceof CannotGetJdbcConnectionException
                    || cause instanceof TransientDataAccessResourceException
                    || cause instanceof CannotCreateTransactionException) {
                return true;
            }
        }
        return false;
    }
}
