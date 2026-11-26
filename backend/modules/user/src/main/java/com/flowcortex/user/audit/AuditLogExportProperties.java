package com.flowcortex.user.audit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Size bound of the audit log export, bound from {@code flowcortex.admin-audit.export-max-rows}: a
 * filter matching more rows is refused instead of producing a partial file.
 */
@ConfigurationProperties("flowcortex.admin-audit")
public record AuditLogExportProperties(@DefaultValue("10000") int exportMaxRows) {

    public AuditLogExportProperties {
        if (exportMaxRows < 1) {
            throw new IllegalArgumentException(
                    "flowcortex.admin-audit.export-max-rows must be at least 1, was " + exportMaxRows);
        }
    }
}
