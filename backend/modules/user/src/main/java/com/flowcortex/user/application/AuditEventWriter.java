package com.flowcortex.user.application;

import java.util.Map;
import java.util.UUID;

/**
 * Writes rows to {@code auth_event}. Implementations fail open: a failed audit
 * row is logged and never thrown, so it cannot block a business operation. The
 * default implementation is {@link DefaultAuditEventWriter}, which writes via JDBC.
 */
public interface AuditEventWriter {

    void write(AuthEventType type, UUID userId, String ip, String userAgent, Map<String, Object> metadata);
}
