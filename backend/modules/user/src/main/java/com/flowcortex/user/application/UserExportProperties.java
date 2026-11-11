package com.flowcortex.user.application;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Bounds of the users CSV export, bound from {@code flowcortex.users.export.*}: a filter matching more
 * than {@code maxRows} accounts is refused instead of producing a partial file.
 */
@Validated
@ConfigurationProperties("flowcortex.users.export")
public record UserExportProperties(@DefaultValue("10000") @Min(1) int maxRows) {
}
