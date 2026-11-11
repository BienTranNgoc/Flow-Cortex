package com.flowcortex.notification.application;

import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Bound from {@code flowcortex.notifications.*}: how many notifications a member keeps. */
@Validated
@ConfigurationProperties("flowcortex.notifications")
record NotificationProperties(@Positive @DefaultValue("500") int maxPerMember) {
}
