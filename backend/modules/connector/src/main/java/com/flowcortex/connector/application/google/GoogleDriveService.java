package com.flowcortex.connector.application.google;

import com.flowcortex.connector.api.ConnectorException;
import com.flowcortex.connector.api.ConnectorException.Reason;
import com.flowcortex.connector.api.DriveFile;
import com.flowcortex.connector.api.DriveFileText;
import com.flowcortex.connector.api.GoogleDrive;
import com.flowcortex.connector.application.google.GoogleErrorRules.Api;
import com.flowcortex.connector.application.google.GoogleErrorRules.Item;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.BytesReply;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Fault;
import com.flowcortex.connector.application.google.GoogleWorkspaceApi.Reply;
import com.flowcortex.shared.web.LocalizedMessage;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Reads a member's Drive folders and file text with the member's own Google token. Every Google refusal goes
 * through {@link GoogleErrorRules}; a file that is too large, unreadable or of a type that is not read is a warning
 * on its {@link DriveFileText}, never a failure.
 */
@Service
public class GoogleDriveService implements GoogleDrive {

    static final int MAX_DOWNLOAD_BYTES = 10 * 1024 * 1024;
    static final int MIN_LIMIT = 1;
    static final int MAX_LIMIT = 50;

    private static final String FOLDER_MIME = "application/vnd.google-apps.folder";
    private static final int MAX_ID_LENGTH = 200;
    private static final String EXPORT_TOO_LARGE = "exportSizeLimitExceeded";
    private static final Logger LOG = LoggerFactory.getLogger(GoogleDriveService.class);

    private final GoogleTokens tokens;
    private final GoogleWorkspaceApi api;
    private final DocumentText documentText;
    private final GoogleProperties properties;

    GoogleDriveService(GoogleTokens tokens, GoogleWorkspaceApi api, DocumentText documentText,
            GoogleProperties properties) {
        this.tokens = tokens;
        this.api = api;
        this.documentText = documentText;
        this.properties = properties;
    }

    @Override
    public boolean enabled() {
        return properties.enabled();
    }

    @Override
    public List<DriveFile> listFolder(UUID memberId, String folderId, int limit) {
        if (folderId == null || folderId.isBlank() || folderId.length() > MAX_ID_LENGTH || limit < MIN_LIMIT
                || limit > MAX_LIMIT) {
            throw GoogleSheetsRules.invalid();
        }
        String token = tokens.forUse(memberId).accessToken();
        Reply folder = api.driveGet(token, folderId);
        if (!folder.ok()) {
            throw failure(folder.status(), folder.fault(), folder.retryAfter(), Item.FOLDER);
        }
        if (!FOLDER_MIME.equals(text(folder.body().get("mimeType")))) {
            throw new ConnectorException(Reason.REFUSED, false, new LocalizedMessage("connector.google.notAFolder"),
                    GoogleErrorRules.NOT_A_FOLDER, null);
        }
        String query = "'" + GoogleSheetsRules.escapeLiteral(folderId) + "' in parents and trashed=false";
        Reply listing = api.driveList(token, query, "name", limit, null);
        if (!listing.ok()) {
            throw failure(listing.status(), listing.fault(), listing.retryAfter(), Item.FOLDER);
        }
        return files(listing.body(), limit);
    }

    @Override
    public DriveFileText readText(UUID memberId, DriveFile file) {
        return switch (DocumentText.classify(file.mimeType())) {
            case GDOC -> export(memberId, file);
            case PDF, DOCX, TEXT -> download(memberId, file);
            case IMAGE, MEDIA, GSUITE, UNSUPPORTED -> documentText.fromBytes(null, file.mimeType());
        };
    }

    private DriveFileText export(UUID memberId, DriveFile file) {
        String token = tokens.forUse(memberId).accessToken();
        BytesReply reply = api.driveExport(token, file.id(), "text/plain", MAX_DOWNLOAD_BYTES);
        if (reply.overLimit() || exportTooLarge(reply)) {
            return DocumentText.tooLarge(file.mimeType());
        }
        requireBody(reply);
        return documentText.fromExportedText(new String(reply.body(), StandardCharsets.UTF_8));
    }

    private DriveFileText download(UUID memberId, DriveFile file) {
        if (file.sizeBytes() != null && file.sizeBytes() > MAX_DOWNLOAD_BYTES) {
            return DocumentText.tooLarge(file.mimeType());
        }
        String token = tokens.forUse(memberId).accessToken();
        BytesReply reply = api.driveDownload(token, file.id(), MAX_DOWNLOAD_BYTES);
        if (reply.overLimit()) {
            return DocumentText.tooLarge(file.mimeType());
        }
        requireBody(reply);
        return documentText.fromBytes(reply.body(), file.mimeType());
    }

    private static boolean exportTooLarge(BytesReply reply) {
        return reply.status() == 403 && reply.fault() != null && EXPORT_TOO_LARGE.equals(reply.fault().reason());
    }

    private static void requireBody(BytesReply reply) {
        if (reply.ok()) {
            return;
        }
        if (reply.status() < 400) {
            LOG.warn("Google answered {} to a Drive file read; redirects are not followed", reply.status());
        }
        throw failure(reply.status(), reply.fault(), reply.retryAfter(), Item.OTHER);
    }

    private static ConnectorException failure(int status, Fault fault, Duration retryAfter, Item item) {
        return GoogleErrorRules.classify(Api.DRIVE, item, status, fault == null ? null : fault.reason(),
                fault == null ? null : fault.domain(), fault == null ? null : fault.message(), retryAfter);
    }

    private static List<DriveFile> files(Map<String, Object> body, int limit) {
        List<DriveFile> files = new ArrayList<>();
        if (!(body.get("files") instanceof List<?> items)) {
            return files;
        }
        for (Object item : items) {
            if (files.size() == limit) {
                break;
            }
            if (item instanceof Map<?, ?> map && text(map.get("id")) != null) {
                String name = text(map.get("name"));
                files.add(new DriveFile(text(map.get("id")), name == null ? "" : name, text(map.get("mimeType")),
                        instant(text(map.get("modifiedTime"))), size(text(map.get("size")))));
            }
        }
        return files;
    }

    private static String text(Object value) {
        return value instanceof String text ? text : null;
    }

    private static Instant instant(String value) {
        try {
            return value == null ? null : Instant.parse(value);
        } catch (DateTimeParseException ex) {
            return null;
        }
    }

    private static Long size(String value) {
        try {
            return value == null ? null : Long.valueOf(value);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
