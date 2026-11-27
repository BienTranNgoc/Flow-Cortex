package com.flowcortex.user.audit;

import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.application.UserCsv;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Builds the audit log CSV. The count and the rows read one snapshot taken before the export's own
 * audit row is inserted (in its own transaction), so the file holds exactly the counted rows and never
 * that row. Over the configured limit, or when the row cannot be recorded, nothing is produced.
 */
@Service
@EnableConfigurationProperties(AuditLogExportProperties.class)
class AuditLogExportService {

    static final String ACTION = "audit_log.export";
    static final String RESOURCE_TYPE = "audit_log";

    private final AuditLogRepository repository;
    private final AuditReadRecorder recorder;
    private final AuditLogExportProperties properties;

    AuditLogExportService(AuditLogRepository repository, AuditReadRecorder recorder,
            AuditLogExportProperties properties) {
        this.repository = repository;
        this.recorder = recorder;
        this.properties = properties;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    byte[] export(UUID callerId, AuditLogFilter filter, String clientAddress, String userAgent) {
        long total = repository.count(filter);
        int max = properties.exportMaxRows();
        if (total > max) {
            throw new UnprocessableEntityException(new LocalizedMessage("audit.exportTooLarge", total, max),
                    "audit.exportTooLarge");
        }
        recorder.recordOrRefuse(callerId, ACTION, RESOURCE_TYPE, null, null, filter.givenAsJson(total),
                clientAddress, userAgent);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        write(out, UserCsv.BOM + AuditLogCsv.header());
        repository.stream(filter, row -> write(out, AuditLogCsv.row(row)));
        return out.toByteArray();
    }

    private static void write(ByteArrayOutputStream out, String text) {
        out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
    }
}
