package com.flowcortex.workflow.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.DriveFile;
import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.api.DriveFolderId;
import com.flowcortex.connector.api.GoogleDrive;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.application.provider.DubProperties;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * {@code drive.read} — the files of one Google Drive folder of the run's owner, each with its text. The folder is
 * listed and read with the owner read from the run, never from the workflow. One file that cannot be read becomes a
 * warning on that file; a failure that no later file could escape, and any failure of the listing, fails the step
 * with a stable code. See {@link DriveReadBudget} for what one run may spend.
 */
@Component
class DriveReadStepExecutor implements StepExecutor {

    private static final Logger LOG = LoggerFactory.getLogger(DriveReadStepExecutor.class);
    static final String TYPE = "drive.read";
    private static final String TURNED_OFF_CODE = "connector_turned_off";
    /** The 240 s read budget, one file in flight (connect 5 s, read 30 s, extraction 20 s) and the margin. */
    private static final Duration MAX_RUNTIME = Duration.ofSeconds(300);

    private static final String NOT_READ = "notRead";
    private static final String OUTPUT_LIMIT = "outputLimit";
    private static final String READ_FAILED = "readFailed";
    private static final String ERROR_TYPE = "error";
    private static final String UNREAD_TYPE = "unread";

    private final ObjectMapper json = new ObjectMapper();
    private final GoogleDrive drive;
    private final WorkflowRunRepository runs;
    private final DubProperties dub;
    private final ObjectProvider<StepExecutor> executors;
    private final Clock clock;

    DriveReadStepExecutor(GoogleDrive drive, WorkflowRunRepository runs, DubProperties dub,
            ObjectProvider<StepExecutor> executors, Clock clock) {
        this.drive = drive;
        this.runs = runs;
        this.dub = dub;
        this.executors = executors;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public Mode mode() {
        return Mode.INLINE;
    }

    @Override
    public Pool pool() {
        return Pool.PROVIDER;
    }

    @Override
    public Duration maxRuntime() {
        return MAX_RUNTIME;
    }

    /** The queue wait assumes every queued task runs at most its argument, so it takes the longest step sharing the pool. */
    @Override
    public Duration queueTimeout(Duration lease) {
        return lease.plus(dub.pool().queueWait(
                ConnectorCallStepExecutor.longestProviderRuntime(MAX_RUNTIME, executors.stream())));
    }

    @Override
    public boolean available() {
        return drive.enabled();
    }

    @Override
    public LocalizedStatusException unavailableRefusal() {
        LocalizedStatusException refusal = new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                new LocalizedMessage("workflow.connectorTurnedOff"));
        refusal.getBody().setProperty("code", TURNED_OFF_CODE);
        return refusal;
    }

    @Override
    public String execute(StepContext context) {
        DriveReadConfig.Result parsed = DriveReadConfig.parse(context.config());
        if (parsed.config() == null) {
            throw ConnectorCallFailures.invalidConfig();
        }
        DriveReadConfig config = parsed.config();
        String folderId = config.folderFromInput() ? folderOfInput(context.input()) : config.folderId();
        UUID owner = runs.findById(context.runId()).map(WorkflowRun::getOwnerId)
                .orElseThrow(ConnectorCallFailures::runNotFound);
        Instant started = clock.instant();
        List<DriveFile> listed = list(owner, folderId, config.maxFiles());

        List<Map<String, Object>> files = new ArrayList<>();
        int textSoFar = 0;
        int readable = 0;
        for (DriveFile file : listed) {
            if (!DriveReadBudget.allowsNextFile(Duration.between(started, clock.instant()))) {
                files.add(entry(file, new DriveFileText("", UNREAD_TYPE, false, false, NOT_READ)));
            } else if (!DriveReadBudget.allowsText(textSoFar)) {
                files.add(entry(file, new DriveFileText("", UNREAD_TYPE, false, false, OUTPUT_LIMIT)));
            } else {
                DriveFileText read = read(owner, file);
                textSoFar += read.text().length();
                readable += read.text().isEmpty() ? 0 : 1;
                files.add(entry(file, read));
            }
        }
        LOG.info("Drive read of a folder id of {} characters listed {} files, {} with text", folderId.length(),
                listed.size(), readable);
        return envelope(folderId, listed.size(), readable, files);
    }

    private static String folderOfInput(String input) {
        if (input == null || input.isBlank() || "null".equals(input.strip())) {
            throw DriveReadFailures.missingFolder();
        }
        Optional<String> folderId = DriveFolderId.parse(input);
        return folderId.orElseThrow(DriveReadFailures::notAFolder);
    }

    private List<DriveFile> list(UUID owner, String folderId, int maxFiles) {
        try {
            return drive.listFolder(owner, folderId, maxFiles);
        } catch (ConnectorException e) {
            LOG.info("Drive folder listing refused: {}", e.reason());
            throw DriveReadFailures.of(e);
        } catch (RuntimeException e) {
            LOG.warn("Drive folder listing failed unexpectedly: {}", e.getClass().getName());
            throw DriveReadFailures.unexpected();
        }
    }

    /** The text of one file; a failure that only concerns this file is a warning on it, any other ends the step. */
    private DriveFileText read(UUID owner, DriveFile file) {
        try {
            DriveFileText read = drive.readText(owner, file);
            return read.text() == null ? new DriveFileText("", read.detectedType(), read.truncated(),
                    read.needsVision(), read.warning()) : read;
        } catch (ConnectorException e) {
            LOG.info("Drive file read refused: {}", e.reason());
            if (DriveReadFailures.stopsStep(e)) {
                throw DriveReadFailures.of(e);
            }
            return new DriveFileText("", ERROR_TYPE, false, false, READ_FAILED);
        } catch (RuntimeException e) {
            LOG.warn("Drive file read failed unexpectedly: {}", e.getClass().getName());
            return new DriveFileText("", ERROR_TYPE, false, false, READ_FAILED);
        }
    }

    private static Map<String, Object> entry(DriveFile file, DriveFileText read) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", file.id());
        entry.put("name", file.name());
        entry.put("mimeType", file.mimeType());
        entry.put("detectedType", read.detectedType());
        entry.put("text", read.text());
        entry.put("truncated", read.truncated());
        entry.put("needsVision", read.needsVision());
        if (read.warning() != null) {
            entry.put("warning", read.warning());
        }
        return entry;
    }

    private String envelope(String folderId, int total, int readable, List<Map<String, Object>> files) {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("folderId", folderId);
        envelope.put("total", total);
        envelope.put("readable", readable);
        envelope.put("files", files);
        try {
            return json.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the drive read output", e);
        }
    }
}
