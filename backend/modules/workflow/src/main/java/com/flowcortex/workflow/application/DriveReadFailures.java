package com.flowcortex.workflow.application;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * The failures of a {@code drive.read} step: one stable code and one fixed sentence per cause. The code is decided
 * from {@link ConnectorException#reason()} and {@link ConnectorException#providerReason()} only, never from a message
 * key, and a sentence carries no account, URL or text from Google.
 */
final class DriveReadFailures {

    private static final String GOOGLE = "google";
    private static final String RECONNECT = "reconnect";
    private static final String SCOPE_MISSING = "scope_missing";

    private record Failure(String code, String message) {
    }

    /** The reasons the connector gate and Google's quota report: they apply to the whole account, not to one file. */
    private static final Set<Reason> ACCOUNT_WIDE = EnumSet.of(Reason.SUSPENDED, Reason.TURNED_OFF,
            Reason.NOT_CONNECTED, Reason.USAGE_DISABLED, Reason.CREDENTIAL_UNREADABLE, Reason.RATE_LIMITED);

    private static final Failure FOLDER_NO_ACCESS = new Failure("drive_folder_no_access",
            "The connected Google account cannot open this folder. Share the folder with that account, "
                    + "or open it to anyone with the link, then run again.");
    private static final Failure NOT_A_FOLDER = new Failure("drive_not_a_folder",
            "The value is not a Google Drive folder. Give the link of a folder, not of a file.");
    private static final Failure RECONNECT_NEEDED = new Failure("drive_reconnect_needed",
            "Google no longer accepts the saved connection. Reconnect Google under Connectors, then run again.");
    private static final Failure SCOPE_MISSING_FAILURE = new Failure("drive_scope_missing",
            "The Google connection does not allow reading Drive. Reconnect Google under Connectors and accept "
                    + "the Drive permission, then run again.");
    private static final Failure FOLDER_MISSING = new Failure("drive_folder_missing",
            "The step has no folder to read. Give it a folder link, or pass one as the step's input.");

    private static final Map<String, Failure> BY_PROVIDER_REASON = Map.of(
            "folder_no_access", FOLDER_NO_ACCESS,
            "not_a_folder", NOT_A_FOLDER,
            RECONNECT, RECONNECT_NEEDED,
            SCOPE_MISSING, SCOPE_MISSING_FAILURE);

    private DriveReadFailures() {
    }

    static StepFailedException of(ConnectorException exception) {
        Reason reason = exception.reason();
        boolean revokedConnection = reason == Reason.NOT_CONNECTED && RECONNECT.equals(exception.providerReason());
        if (reason == Reason.REFUSED || revokedConnection) {
            String providerReason = exception.providerReason();
            Failure failure = providerReason == null ? null : BY_PROVIDER_REASON.get(providerReason);
            return failure == null ? unexpected() : failed(failure);
        }
        if (ACCOUNT_WIDE.contains(reason) || reason == Reason.UPSTREAM_UNAVAILABLE) {
            return ConnectorCallFailures.of(exception, GOOGLE);
        }
        return unexpected();
    }

    /** Whether the failure of one file's read ends the whole step because no later file could be read either. */
    static boolean stopsStep(ConnectorException exception) {
        if (ACCOUNT_WIDE.contains(exception.reason())) {
            return true;
        }
        return exception.reason() == Reason.REFUSED && (RECONNECT.equals(exception.providerReason())
                || SCOPE_MISSING.equals(exception.providerReason()));
    }

    static StepFailedException missingFolder() {
        return failed(FOLDER_MISSING);
    }

    static StepFailedException notAFolder() {
        return failed(NOT_A_FOLDER);
    }

    /** Anything the read threw that is not a {@link ConnectorException}. */
    static StepFailedException unexpected() {
        return ConnectorCallFailures.unexpected();
    }

    private static StepFailedException failed(Failure failure) {
        return new StepFailedException(failure.code(), failure.message());
    }
}
