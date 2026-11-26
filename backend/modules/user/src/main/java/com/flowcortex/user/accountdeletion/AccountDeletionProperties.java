package com.flowcortex.user.accountdeletion;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * The configured layer of the two account deletion settings, read on every call by the settings
 * registry. {@code enabled} is {@code null} when unset; {@code false} vetoes the switch whatever an
 * operator stored.
 */
@Validated
@ConfigurationProperties("flowcortex.account-deletion")
record AccountDeletionProperties(Boolean enabled, @Min(1) @Max(365) int gracePeriodDays) {
}
