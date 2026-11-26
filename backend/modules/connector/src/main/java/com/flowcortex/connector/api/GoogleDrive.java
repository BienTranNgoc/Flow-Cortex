package com.flowcortex.connector.api;

import java.util.List;
import java.util.UUID;

/** Server-side access to a member's Google Drive, for workflow steps. */
public interface GoogleDrive {

    /** Whether the Google connector is switched on; off, no member can use it. */
    boolean enabled();

    /**
     * The non-trashed direct children of the folder, by name, at most {@code limit} (1-50). {@code memberId} is the
     * owner of the run, never a caller-chosen id.
     *
     * @throws ConnectorException for every failure; a non-folder id is {@code REFUSED} with provider reason
     *         {@code not_a_folder}, a folder the account cannot open {@code folder_no_access}
     */
    List<DriveFile> listFolder(UUID memberId, String folderId, int limit);

    /**
     * The text of one file within fixed size, character and time bounds; a bound or an unreadable file is a warning
     * on the result, not a failure.
     *
     * @throws ConnectorException when Google or the connection refuses the request
     */
    DriveFileText readText(UUID memberId, DriveFile file);
}
