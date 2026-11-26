package com.flowcortex.workflow.application;

import static com.flowcortex.workflow.EnglishBundle.english;
import static com.flowcortex.workflow.application.DubFixtures.dub;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.DriveFile;
import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.api.GoogleDrive;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import com.flowcortex.workflow.domain.WorkflowRun;
import com.flowcortex.workflow.infrastructure.WorkflowRunRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/** {@code drive.read}: the run owner's folder, one envelope, bounded in time and size, one code per failure. */
class DriveReadStepExecutorTest {

    private static final String FOLDER = "1AbCdEfGhIjK";
    private static final String LINK = "https://drive.google.com/drive/folders/" + FOLDER;
    private static final String CONFIG = "{\"folderId\":\"" + LINK + "?usp=sharing\",\"maxFiles\":10}";
    private static final String FROM_INPUT = "{\"folderFromInput\":true}";

    private final ObjectMapper json = new ObjectMapper();
    private final GoogleDrive drive = mock(GoogleDrive.class);
    private final WorkflowRunRepository runs = mock(WorkflowRunRepository.class);
    private final UUID owner = UUID.randomUUID();
    private final UUID runId = UUID.randomUUID();
    private final TickingClock clock = new TickingClock();

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StepExecutor> others = mock(ObjectProvider.class);

    private DriveReadStepExecutor executor() {
        when(others.stream()).thenAnswer(call -> Stream.<StepExecutor>empty());
        when(runs.findById(runId)).thenReturn(
                Optional.of(new WorkflowRun(runId, UUID.randomUUID(), owner, null, Instant.now())));
        return new DriveReadStepExecutor(drive, runs, dub(), others, clock);
    }

    private StepContext context(String config, String input) {
        return new StepContext(runId, UUID.randomUUID(), 1, "drive.read", (short) 1, input, config, null);
    }

    private static DriveFile file(int number, String mimeType) {
        return new DriveFile("id" + number, "file" + number, mimeType, Instant.parse("2026-11-01T00:00:00Z"), 1000L);
    }

    private static List<DriveFile> files(int count) {
        List<DriveFile> files = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            files.add(file(i, "application/pdf"));
        }
        return files;
    }

    private static DriveFileText text(String text) {
        return new DriveFileText(text, "pdf", false, false, null);
    }

    private static ConnectorException refused(String providerReason) {
        return new ConnectorException(Reason.REFUSED, false, new LocalizedMessage("connector.google.rejected"),
                providerReason, null);
    }

    private static ConnectorException rateLimited() {
        return new ConnectorException(Reason.RATE_LIMITED, true, new LocalizedMessage("connector.google.rateLimited"),
                null, null);
    }

    private JsonNode run(String config, String input) throws Exception {
        return json.readTree(executor().execute(context(config, input)));
    }

    private static StepFailedException failureOf(Runnable step) {
        return org.junit.jupiter.api.Assertions.assertThrows(StepFailedException.class, step::run);
    }

    @Test
    void theFolderOfTheConfigIsListedForTheRunOwnerWithTheConfiguredMaximum() throws Exception {
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(List.of());

        JsonNode output = run(CONFIG, null);

        verify(drive).listFolder(owner, FOLDER, 10);
        assertThat(output.path("folderId").asText()).isEqualTo(FOLDER);
    }

    @Test
    void theFolderOfTheInputIsReadWhenTheConfigAsksForIt() throws Exception {
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(List.of());

        run(FROM_INPUT, LINK);

        verify(drive).listFolder(owner, FOLDER, 20);
    }

    @Test
    void anEmptyOrNullInputIsAMissingFolderAndGoogleIsNotCalled() {
        DriveReadStepExecutor executor = executor();
        for (String input : new String[] {null, "", "   ", "null", " null "}) {
            assertThat(failureOf(() -> executor.execute(context(FROM_INPUT, input))).code())
                    .as(String.valueOf(input)).isEqualTo("drive_folder_missing");
        }

        verify(drive, never()).listFolder(any(), any(), anyInt());
    }

    @Test
    void anInputThatIsNotAFolderIsRefusedAndGoogleIsNotCalled() {
        StepFailedException failure = failureOf(() -> executor().execute(context(FROM_INPUT, "see attached file")));

        assertThat(failure.code()).isEqualTo("drive_not_a_folder");
        verify(drive, never()).listFolder(any(), any(), anyInt());
    }

    @Test
    void aConfigThatDoesNotParseFailsWithTheInvalidRequestCode() {
        assertThat(failureOf(() -> executor().execute(context("{}", null))).code())
                .isEqualTo("connector_invalid_request");
        assertThat(failureOf(() -> executor().execute(context(null, null))).code())
                .isEqualTo("connector_invalid_request");
    }

    @Test
    void aRunThatCannotBeFoundFailsWithTheInvalidRequestCode() {
        DriveReadStepExecutor executor = executor();
        when(runs.findById(runId)).thenReturn(Optional.empty());

        assertThat(failureOf(() -> executor.execute(context(CONFIG, null))).code())
                .isEqualTo("connector_invalid_request");
        verify(drive, never()).listFolder(any(), any(), anyInt());
    }

    @Test
    void theEnvelopeCountsEveryFileAndOnlyTheOnesWithText() throws Exception {
        DriveFile pdf = new DriveFile("a1", "a.pdf", "application/pdf", Instant.parse("2026-11-01T00:00:00Z"), 5000L);
        DriveFile png = new DriveFile("b1", "b.png", "image/png", null, 900L);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(List.of(pdf, png));
        when(drive.readText(owner, pdf)).thenReturn(new DriveFileText("Nguyen Van A", "pdf", false, false, null));
        when(drive.readText(owner, png)).thenReturn(new DriveFileText("", "image", false, true, null));

        JsonNode output = run(CONFIG, null);

        assertThat(output.fieldNames()).toIterable().containsExactly("folderId", "total", "readable", "files");
        assertThat(output.path("total").asInt()).isEqualTo(2);
        assertThat(output.path("readable").asInt()).isEqualTo(1);
        JsonNode first = output.path("files").get(0);
        assertThat(first.fieldNames()).toIterable()
                .containsExactly("id", "name", "mimeType", "detectedType", "text", "truncated", "needsVision");
        assertThat(first.path("id").asText()).isEqualTo("a1");
        assertThat(first.path("name").asText()).isEqualTo("a.pdf");
        assertThat(first.path("mimeType").asText()).isEqualTo("application/pdf");
        assertThat(first.path("detectedType").asText()).isEqualTo("pdf");
        assertThat(first.path("text").asText()).isEqualTo("Nguyen Van A");
        assertThat(first.path("truncated").asBoolean()).isFalse();
        assertThat(first.path("needsVision").asBoolean()).isFalse();
        JsonNode second = output.path("files").get(1);
        assertThat(second.path("text").asText()).isEmpty();
        assertThat(second.path("needsVision").asBoolean()).isTrue();
        assertThat(second.has("warning")).isFalse();
    }

    @Test
    void aWarningFromTheReaderIsCarriedOnItsFile() throws Exception {
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(files(1));
        when(drive.readText(any(), any())).thenReturn(new DriveFileText("", "pdf", false, false, "encrypted"));

        JsonNode output = run(CONFIG, null);

        assertThat(output.path("files").get(0).path("warning").asText()).isEqualTo("encrypted");
        assertThat(output.path("readable").asInt()).isZero();
    }

    @Test
    void anEmptyFolderCompletesWithNothing() throws Exception {
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(List.of());

        JsonNode output = run(CONFIG, null);

        assertThat(output.path("total").asInt()).isZero();
        assertThat(output.path("readable").asInt()).isZero();
        assertThat(output.path("files").isArray()).isTrue();
        assertThat(output.path("files")).isEmpty();
        verify(drive, never()).readText(any(), any());
    }

    @Test
    void aFileGoogleCannotBeReachedForIsAnErrorFileAndTheStepCompletes() throws Exception {
        List<DriveFile> listed = files(3);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(owner, listed.get(0))).thenReturn(text("one"));
        when(drive.readText(owner, listed.get(1))).thenThrow(ConnectorException.of(Reason.UPSTREAM_UNAVAILABLE));
        when(drive.readText(owner, listed.get(2))).thenReturn(text("three"));

        JsonNode output = run(CONFIG, null);

        JsonNode failed = output.path("files").get(1);
        assertThat(failed.path("detectedType").asText()).isEqualTo("error");
        assertThat(failed.path("warning").asText()).isEqualTo("readFailed");
        assertThat(failed.path("text").asText()).isEmpty();
        assertThat(output.path("readable").asInt()).isEqualTo(2);
        assertThat(output.path("files").get(2).path("text").asText()).isEqualTo("three");
    }

    @Test
    void anUnexpectedFailureOfOneFileIsAnErrorFileWithoutItsText() throws Exception {
        List<DriveFile> listed = files(2);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(owner, listed.get(0))).thenThrow(new IllegalStateException("token ya29.secret"));
        when(drive.readText(owner, listed.get(1))).thenReturn(text("two"));

        String output = executor().execute(context(CONFIG, null));

        assertThat(output).doesNotContain("ya29.secret").contains("\"warning\":\"readFailed\"");
    }

    @Test
    void aFileThatIsNotFoundOrRejectedIsThatFilesFailureAndTheOthersAreRead() throws Exception {
        List<DriveFile> listed = files(10);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        for (int i = 0; i < 10; i++) {
            when(drive.readText(owner, listed.get(i))).thenReturn(text("t" + i));
        }
        when(drive.readText(owner, listed.get(2))).thenThrow(refused("not_found"));

        JsonNode output = run(CONFIG, null);

        verify(drive, times(10)).readText(eq(owner), any());
        assertThat(output.path("files").get(2).path("warning").asText()).isEqualTo("readFailed");
        assertThat(output.path("readable").asInt()).isEqualTo(9);
    }

    @Test
    void aUsagePolicyFailureWhileReadingStopsTheStep() {
        List<DriveFile> listed = files(3);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(owner, listed.get(0))).thenThrow(ConnectorException.of(Reason.USAGE_DISABLED));

        assertThat(failureOf(() -> executor().execute(context(CONFIG, null))).code())
                .isEqualTo("connector_usage_disabled");
        verify(drive, times(1)).readText(any(), any());
    }

    @Test
    void aRateLimitOnTheThirdOfTenFilesStopsTheStepAndNoLaterFileIsRead() {
        List<DriveFile> listed = files(10);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(any(), any())).thenReturn(text("t"));
        when(drive.readText(owner, listed.get(2))).thenThrow(rateLimited());

        assertThat(failureOf(() -> executor().execute(context(CONFIG, null))).code())
                .isEqualTo("connector_rate_limited");
        verify(drive, times(3)).readText(eq(owner), any());
        verify(drive, never()).readText(owner, listed.get(3));
    }

    @Test
    void aRejectedTokenOnTheThirdOfTenFilesStopsTheStepAndNoLaterFileIsRead() {
        List<DriveFile> listed = files(10);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(any(), any())).thenReturn(text("t"));
        when(drive.readText(owner, listed.get(2))).thenThrow(refused("reconnect"));

        assertThat(failureOf(() -> executor().execute(context(CONFIG, null))).code())
                .isEqualTo("drive_reconnect_needed");
        verify(drive, times(3)).readText(eq(owner), any());
    }

    @Test
    void aMissingScopeWhileReadingStopsTheStep() {
        List<DriveFile> listed = files(3);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(owner, listed.get(0))).thenThrow(refused("scope_missing"));

        assertThat(failureOf(() -> executor().execute(context(CONFIG, null))).code())
                .isEqualTo("drive_scope_missing");
    }

    @Test
    void noFileStartsOnceTwoHundredAndFortySecondsHavePassed() throws Exception {
        List<DriveFile> listed = files(20);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        int[] reads = {0};
        when(drive.readText(any(), any())).thenAnswer(call -> {
            reads[0]++;
            if (reads[0] == 12) {
                clock.advance(Duration.ofSeconds(241));
            }
            return text("t");
        });

        JsonNode output = run("{\"folderId\":\"" + FOLDER + "\",\"maxFiles\":20}", null);

        verify(drive, times(12)).readText(eq(owner), any());
        assertThat(output.path("total").asInt()).isEqualTo(20);
        assertThat(output.path("readable").asInt()).isEqualTo(12);
        for (int i = 12; i < 20; i++) {
            JsonNode skipped = output.path("files").get(i);
            assertThat(skipped.path("warning").asText()).as("file " + (i + 1)).isEqualTo("notRead");
            assertThat(skipped.path("text").asText()).isEmpty();
            assertThat(skipped.path("id").asText()).isEqualTo("id" + (i + 1));
        }
    }

    @Test
    void textStopsBeingAddedOnceTheOutputHoldsHalfAMillionCharacters() throws Exception {
        List<DriveFile> listed = files(20);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(any(), any())).thenReturn(text("x".repeat(50_000)));

        JsonNode output = run("{\"folderId\":\"" + FOLDER + "\",\"maxFiles\":20}", null);

        verify(drive, times(10)).readText(eq(owner), any());
        assertThat(output.path("readable").asInt()).isEqualTo(10);
        for (int i = 0; i < 10; i++) {
            assertThat(output.path("files").get(i).path("text").asText()).hasSize(50_000);
        }
        for (int i = 10; i < 20; i++) {
            JsonNode skipped = output.path("files").get(i);
            assertThat(skipped.path("warning").asText()).as("file " + (i + 1)).isEqualTo("outputLimit");
            assertThat(skipped.path("text").asText()).isEmpty();
        }
    }

    @Test
    void aFolderTheAccountCannotOpenFailsWithItsCode() {
        when(drive.listFolder(any(), any(), anyInt())).thenThrow(refused("folder_no_access"));

        StepFailedException failure = failureOf(() -> executor().execute(context(CONFIG, null)));

        assertThat(failure.code()).isEqualTo("drive_folder_no_access");
        assertThat(failure.getMessage()).doesNotContain("@");
    }

    @Test
    void aFolderThatIsAFileFailsWithItsCode() {
        when(drive.listFolder(any(), any(), anyInt())).thenThrow(refused("not_a_folder"));

        assertThat(failureOf(() -> executor().execute(context(CONFIG, null))).code()).isEqualTo("drive_not_a_folder");
    }

    @Test
    void aGoogleThatIsNotConnectedNamesGoogle() {
        when(drive.listFolder(any(), any(), anyInt())).thenThrow(ConnectorException.of(Reason.NOT_CONNECTED));

        StepFailedException failure = failureOf(() -> executor().execute(context(CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_not_connected");
        assertThat(failure.getMessage()).contains("google");
    }

    @Test
    void aFailureThatIsNotAConnectorFailureIsUpstreamUnavailableWithoutItsText() {
        when(drive.listFolder(any(), any(), anyInt())).thenThrow(new IllegalStateException("token ya29.secret"));

        StepFailedException failure = failureOf(() -> executor().execute(context(CONFIG, null)));

        assertThat(failure.code()).isEqualTo("connector_upstream_unavailable");
        assertThat(failure.getMessage()).doesNotContain("ya29.secret");
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void theStepRunsInlineOnTheProviderPoolWithinFiveMinutes() {
        DriveReadStepExecutor executor = executor();

        assertThat(executor.type()).isEqualTo("drive.read");
        assertThat(executor.mode()).isEqualTo(StepExecutor.Mode.INLINE);
        assertThat(executor.pool()).isEqualTo(StepExecutor.Pool.PROVIDER);
        assertThat(executor.maxRuntime()).isEqualTo(Duration.ofSeconds(300));
    }

    @Test
    void theQueueWaitAssumesTheLongestStepSharingThePool() {
        DriveReadStepExecutor executor = executor();
        StepExecutor longest = mock(StepExecutor.class);
        when(longest.pool()).thenReturn(StepExecutor.Pool.PROVIDER);
        when(longest.maxRuntime()).thenReturn(Duration.ofMinutes(10));
        when(others.stream()).thenAnswer(call -> Stream.of(longest));
        Duration lease = Duration.ofMinutes(2);

        assertThat(executor.queueTimeout(lease)).isEqualTo(lease.plus(dub().pool().queueWait(Duration.ofMinutes(10))));
    }

    @Test
    void theQueueWaitFloorIsItsOwnRuntime() {
        DriveReadStepExecutor executor = executor();
        Duration lease = Duration.ofMinutes(2);

        assertThat(executor.queueTimeout(lease)).isEqualTo(lease.plus(dub().pool().queueWait(Duration.ofSeconds(300))));
    }

    @Test
    void itIsAvailableExactlyWhileTheGoogleConnectorIsOn() {
        DriveReadStepExecutor executor = executor();

        when(drive.enabled()).thenReturn(true);
        assertThat(executor.available()).isTrue();
        when(drive.enabled()).thenReturn(false);
        assertThat(executor.available()).isFalse();
    }

    @Test
    void whenUnavailableTheRefusalIsA503ConnectorTurnedOff() {
        LocalizedStatusException refusal = executor().unavailableRefusal();

        assertThat(refusal.getStatusCode().value()).isEqualTo(503);
        assertThat(refusal.getBody().getProperties()).containsEntry("code", "connector_turned_off");
        assertThat(refusal.getDetailMessageCode()).isEqualTo("workflow.connectorTurnedOff");
        assertThat(english(refusal)).startsWith("This workflow uses a connector that is switched off");
    }

    @Test
    void theRefusalIsNotTheDubbingRefusal() {
        assertThat(executor().unavailableRefusal()).isNotInstanceOf(DubDisabledException.class);
    }

    @Test
    void nothingBesidesTheListAndTheReadsIsCalledOnTheDrive() {
        List<DriveFile> listed = files(1);
        when(drive.listFolder(any(), any(), anyInt())).thenReturn(listed);
        when(drive.readText(any(), any())).thenReturn(text("t"));

        executor().execute(context(CONFIG, null));

        verify(drive).listFolder(owner, FOLDER, 10);
        verify(drive).readText(owner, listed.get(0));
        verifyNoMoreInteractions(drive);
    }

    /** A clock that only moves when a test says so. */
    private static final class TickingClock extends Clock {

        private Instant now = Instant.parse("2026-11-20T10:00:00Z");

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
