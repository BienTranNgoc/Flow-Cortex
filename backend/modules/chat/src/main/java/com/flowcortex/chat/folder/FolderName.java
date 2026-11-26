package com.flowcortex.chat.folder;

import com.flowcortex.chat.web.ChatProblemException;
import com.flowcortex.shared.web.LocalizedMessage;
import java.util.Map;

/** A folder name after validation: trimmed, 1 to {@value #MAX_CHARS} code points. {@link #parse} is the only way in. */
public record FolderName(String value) {

    public static final int MAX_CHARS = 60;

    /** Refuses a missing, blank or over-long name as 400 {@code validation-failed} on the field {@code name}. */
    public static FolderName parse(String raw) {
        String name = raw == null ? "" : raw.strip();
        if (name.isEmpty() || name.codePointCount(0, name.length()) > MAX_CHARS) {
            throw ChatProblemException.validationFailed(
                    Map.of("name", new LocalizedMessage("chat.folder.name.invalid", MAX_CHARS)));
        }
        return new FolderName(name);
    }
}
