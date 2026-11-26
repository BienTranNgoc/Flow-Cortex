package com.flowcortex.user.audit;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Records a read that releases member data (an identity reveal, an unmasked export) before the data
 * leaves, and refuses the request with 503 when the row cannot be written, so nothing is released
 * unrecorded. The caller passes the client address and user agent as plain strings.
 */
@Component
public class AuditReadRecorder {

    private final JdbcAdminAuditWriter writer;
    private final AdminAuditActorReader actorReader;

    AuditReadRecorder(JdbcAdminAuditWriter writer, AdminAuditActorReader actorReader) {
        this.writer = writer;
        this.actorReader = actorReader;
    }

    public void recordOrRefuse(UUID actorId, String action, String resourceType, String resourceId, String label,
            JsonNode summary, String clientAddress, String userAgent) {
        AdminAuditEntry entry = new AdminAuditEntry(actorId, actorReader.read(actorId), action, resourceType,
                resourceId, label, summary, AdminAuditEntry.Status.SUCCESS, null, clientAddress, cut(userAgent));
        try {
            writer.writeOrThrow(entry);
        } catch (RuntimeException ex) {
            throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    new LocalizedMessage("audit.recordUnavailable"));
        }
    }

    private static String cut(String value) {
        return value == null || value.length() <= AdminAuditInterceptor.MAX_USER_AGENT_LENGTH
                ? value
                : value.substring(0, AdminAuditInterceptor.MAX_USER_AGENT_LENGTH);
    }
}
