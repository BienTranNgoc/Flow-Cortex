package com.flowcortex.workflow.application;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.workflow.api.runhistory.RunHistoryRequestException;
import java.util.Optional;

/** The rule for a member's own name for a run. */
final class RunTitles {

    static final int MAX_LENGTH = 120;

    private RunTitles() {
    }

    /**
     * The trimmed title, or empty when none is left (which clears it).
     * @throws RunHistoryRequestException if it holds a control character or exceeds {@value #MAX_LENGTH}
     *         characters (counted in code points)
     */
    static Optional<String> normalize(String raw) {
        if (raw == null) {
            return Optional.empty();
        }
        if (raw.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid();
        }
        String title = raw.strip();
        if (title.isEmpty()) {
            return Optional.empty();
        }
        if (title.codePointCount(0, title.length()) > MAX_LENGTH) {
            throw invalid();
        }
        return Optional.of(title);
    }

    private static RunHistoryRequestException invalid() {
        return new RunHistoryRequestException("title", new LocalizedMessage("workflow.run.title.invalid"));
    }
}
