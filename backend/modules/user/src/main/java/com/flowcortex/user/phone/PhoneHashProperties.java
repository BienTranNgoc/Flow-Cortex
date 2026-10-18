package com.flowcortex.user.phone;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * {@code flowcortex.phone.hash-secret} ({@code FLOWCORTEX_PHONE_HASH_SECRET}): the HMAC key for
 * {@link PhoneNumberHasher}. Validated there, because it is only required while phone verification is on.
 */
@ConfigurationProperties("flowcortex.phone")
public record PhoneHashProperties(String hashSecret) {
}
