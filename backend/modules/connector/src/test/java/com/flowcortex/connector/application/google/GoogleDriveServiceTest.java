package com.flowcortex.connector.application.google;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.DriveFile;
import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.application.google.GoogleTokens.GoogleAccess;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.BytesReply;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GoogleDriveServiceTest {

    private static final String FOLDER = "application/vnd.google-apps.folder";
    private static final String PDF = "application/pdf";
    private static final int TEN_MB = 10 * 1024 * 1024;
    private static final String TOKEN = "access-token";

    private final GoogleTokens tokens = mock(GoogleTokens.class);
    private final GoogleWorkspaceApi api = mock(GoogleWorkspaceApi.class);
    private final DocumentText documentText = mock(DocumentText.class);
    private final GoogleProperties properties = new GoogleProperties(true, "cid", "csecret",
            "https://app.example/api/v1/connectors/google/callback", null, null, null, null, null, null);
    private final GoogleDriveService drive = new GoogleDriveService(tokens, api, documentText, properties);
    private final UUID member = UUID.randomUUID();

    private void tokenIssued() {
        when(tokens.forUse(member)).thenReturn(new GoogleAccess(TOKEN, null));
    }

    private static Reply folderReply(String mimeType) {
        return new Reply(200, Map.of("id", "1AbCdEfGhIj", "name", "CVs", "mimeType", mimeType), null, null);
    }

    @SafeVarargs
    private static Reply listReply(Map<String, String>... files) {
        return new Reply(200, Map.of("files", List.of(files)), null, null);
    }

    private static Reply error(int status, String reason) {
        return new Reply(status, Map.of(), new Fault(reason, null, "Google text"), null);
    }

    private static DriveFile file(String mimeType, Long size) {
        return new DriveFile("f1", "cv", mimeType, Instant.parse("2026-11-01T10:00:00Z"), size);
    }

    @Test
    void listingChecksTheItemFirstAndRefusesANonFolderWithoutListing() {
        tokenIssued();
        when(api.driveGet(TOKEN, "1AbCdEfGhIj")).thenReturn(folderReply(PDF));

        assertThatThrownBy(() -> drive.listFolder(member, "1AbCdEfGhIj", 20))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.providerReason()).isEqualTo("not_a_folder");
                    assertThat(ex.detail().code()).isEqualTo("connector.google.notAFolder");
                });

        verify(api, never()).driveList(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void aFolderTheAccountCannotOpenIsRefusedNotListedEmpty() {
        tokenIssued();
        when(api.driveGet(TOKEN, "1AbCdEfGhIj")).thenReturn(error(404, "notFound"));

        assertThatThrownBy(() -> drive.listFolder(member, "1AbCdEfGhIj", 20))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.REFUSED);
                    assertThat(ex.providerReason()).isEqualTo("folder_no_access");
                    assertThat(ex.detail().code()).isEqualTo("connector.google.folderNoAccess");
                });
        verify(api, never()).driveList(anyString(), anyString(), anyString(), anyInt(), any());
    }

    @Test
    void listingAsksForNonTrashedChildrenByNameAndMapsThem() {
        tokenIssued();
        when(api.driveGet(TOKEN, "1Ab'Cd")).thenReturn(folderReply(FOLDER));
        when(api.driveList(eq(TOKEN), anyString(), anyString(), anyInt(), any())).thenReturn(listReply(
                Map.of("id", "a", "name", "a.docx", "mimeType", "application/x-docx", "size", "1200",
                        "modifiedTime", "2026-11-01T10:00:00.000Z"),
                Map.of("id", "b", "name", "b.pdf", "mimeType", PDF, "modifiedTime", "not a time"),
                Map.of("id", "c", "name", "c.txt", "mimeType", "text/plain")));

        List<DriveFile> files = drive.listFolder(member, "1Ab'Cd", 2);

        verify(api).driveList(TOKEN, "'1Ab\\'Cd' in parents and trashed=false", "name", 2, null);
        assertThat(files).containsExactly(
                new DriveFile("a", "a.docx", "application/x-docx", Instant.parse("2026-11-01T10:00:00Z"), 1200L),
                new DriveFile("b", "b.pdf", PDF, null, null));
    }

    @Test
    void aLimitOutsideOneToFiftyIsInvalidInputWithoutAnyRequest() {
        for (int limit : new int[] {0, 51}) {
            assertThatThrownBy(() -> drive.listFolder(member, "1AbCdEfGhIj", limit))
                    .isInstanceOfSatisfying(ConnectorException.class,
                            ex -> assertThat(ex.reason()).isEqualTo(Reason.INVALID_INPUT));
        }
        assertThatThrownBy(() -> drive.listFolder(member, " ", 5))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.INVALID_INPUT));
        verifyNoInteractions(api, tokens);
    }

    @Test
    void aGoogleDocIsExportedAsPlainText() {
        tokenIssued();
        DriveFileText expected = new DriveFileText("body", "gdoc", false, false, null);
        when(api.driveExport(TOKEN, "f1", "text/plain", TEN_MB)).thenReturn(
                new BytesReply(200, "text/plain", "body".getBytes(StandardCharsets.UTF_8), false, null, null));
        when(documentText.fromExportedText("body")).thenReturn(expected);

        assertThat(drive.readText(member, file("application/vnd.google-apps.document", null))).isEqualTo(expected);
    }

    @Test
    void anExportTooBigForGoogleIsTooLargeNotAFailure() {
        tokenIssued();
        when(api.driveExport(TOKEN, "f1", "text/plain", TEN_MB)).thenReturn(
                new BytesReply(403, "application/json", new byte[0], false,
                        new Fault("exportSizeLimitExceeded", "global", "Google text"), null));

        assertThat(drive.readText(member, file("application/vnd.google-apps.document", null)).warning())
                .isEqualTo("tooLarge");
    }

    @Test
    void typesThatAreNotReadMakeNoRequestAndTakeNoToken() {
        DriveFileText sheet = new DriveFileText("", "gsuite", false, false, "googleFileType");
        DriveFileText video = new DriveFileText("", "media", false, false, "media");
        when(documentText.fromBytes(isNull(), eq("application/vnd.google-apps.spreadsheet"))).thenReturn(sheet);
        when(documentText.fromBytes(isNull(), eq("video/mp4"))).thenReturn(video);

        assertThat(drive.readText(member, file("application/vnd.google-apps.spreadsheet", null))).isEqualTo(sheet);
        assertThat(drive.readText(member, file("video/mp4", 5_000_000L))).isEqualTo(video);

        verifyNoInteractions(api, tokens);
    }

    @Test
    void aFileOverTenMegabytesIsTooLargeWithoutADownload() {
        DriveFileText result = drive.readText(member, file(PDF, 25L * 1024 * 1024));

        assertThat(result.warning()).isEqualTo("tooLarge");
        assertThat(result.text()).isEmpty();
        verifyNoInteractions(api);
    }

    @Test
    void aFileWithoutASizeWhoseDownloadPassesTheLimitIsTooLarge() {
        tokenIssued();
        when(api.driveDownload(TOKEN, "f1", TEN_MB))
                .thenReturn(new BytesReply(200, PDF, new byte[TEN_MB], true, null, null));

        assertThat(drive.readText(member, file(PDF, null)).warning()).isEqualTo("tooLarge");
        verify(documentText, never()).fromBytes(any(), anyString());
    }

    @Test
    void aPdfIsDownloadedWithinTheLimitAndItsBytesGoToTheExtractor() {
        tokenIssued();
        byte[] bytes = {1, 2, 3};
        DriveFileText expected = new DriveFileText("text", "pdf", false, false, null);
        when(api.driveDownload(TOKEN, "f1", TEN_MB)).thenReturn(new BytesReply(200, PDF, bytes, false, null, null));
        when(documentText.fromBytes(bytes, PDF)).thenReturn(expected);

        assertThat(drive.readText(member, file(PDF, (long) TEN_MB))).isEqualTo(expected);
        verify(tokens).forUse(member);
    }

    @Test
    void aRedirectAnswerIsAnUpstreamFailureWithoutAProviderReason() {
        tokenIssued();
        when(api.driveDownload(TOKEN, "f1", TEN_MB))
                .thenReturn(new BytesReply(302, null, new byte[0], false, null, null));
        when(api.driveExport(TOKEN, "f1", "text/plain", TEN_MB))
                .thenReturn(new BytesReply(307, null, new byte[0], false, null, null));

        assertThatThrownBy(() -> drive.readText(member, file(PDF, 100L)))
                .isInstanceOfSatisfying(ConnectorException.class, ex -> {
                    assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE);
                    assertThat(ex.providerReason()).isNull();
                });
        assertThatThrownBy(() -> drive.readText(member, file("application/vnd.google-apps.document", null)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.UPSTREAM_UNAVAILABLE));
    }

    @Test
    void aRefusedDownloadIsClassifiedThroughTheErrorTable() {
        tokenIssued();
        when(api.driveDownload(TOKEN, "f1", TEN_MB)).thenReturn(
                new BytesReply(401, "application/json", new byte[0], false, new Fault("authError", null, null), null));

        assertThatThrownBy(() -> drive.readText(member, file(PDF, 100L)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.providerReason()).isEqualTo("reconnect"));
    }

    @Test
    void whenUsageIsOffNoRequestIsMade() {
        when(tokens.forUse(member)).thenThrow(ConnectorException.of(Reason.USAGE_DISABLED));

        assertThatThrownBy(() -> drive.readText(member, file(PDF, 100L)))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.USAGE_DISABLED));
        assertThatThrownBy(() -> drive.listFolder(member, "1AbCdEfGhIj", 5))
                .isInstanceOfSatisfying(ConnectorException.class,
                        ex -> assertThat(ex.reason()).isEqualTo(Reason.USAGE_DISABLED));
        verifyNoInteractions(api);
    }

    @Test
    void enabledFollowsTheConnectorSwitch() {
        assertThat(drive.enabled()).isTrue();
        GoogleProperties off = new GoogleProperties(false, null, null, null, null, null, null, null, null, null);
        assertThat(new GoogleDriveService(tokens, api, documentText, off).enabled()).isFalse();
    }
}
