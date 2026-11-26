package com.flowcortex.user.phone;

import java.net.URI;
import java.time.Duration;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The VietGuys SMS Brandname account, bound from {@code flowcortex.sms.vietguys.*}. Binding checks
 * nothing: {@link VietguysConfig} validates every value when the sender is selected and names the
 * property, never the value.
 *
 * @param refreshToken the seed of the token chain, read once at startup
 * @param keys credential keyring, key id to base64 of 32 bytes
 */
@ConfigurationProperties("flowcortex.sms.vietguys")
public record VietguysProperties(
        URI smsUrl,
        URI tokenUrl,
        String username,
        String brandname,
        String refreshToken,
        Map<String, String> keys,
        String currentKeyId,
        Templates templates,
        Duration connectTimeout,
        Duration readTimeout) {

    public VietguysProperties {
        keys = keys == null ? Map.of() : Map.copyOf(keys);
    }

    /** The three registered message texts; see {@link SmsTemplates}. */
    public record Templates(String otp, String phoneChanged, String phoneChangeReverted) {
    }

    /** Never prints a value: this record holds a seed and key material. */
    @Override
    public String toString() {
        return "VietguysProperties[...]";
    }
}
