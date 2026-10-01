package com.flowcortex.app.observability;

import io.micrometer.common.KeyValue;
import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationFilter;
import java.util.regex.Pattern;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Strips the query from every URL an observation records, then replaces any signing value still
 * left anywhere in a value (a URL inside an error message, say), so neither spans nor metric tags
 * carry the credential and signature of a presigned object-store URL.
 */
@Configuration(proxyBeanMethods = false)
class TelemetryRedactionConfig {

    /** An absolute URL or a request target: the query is everything from the first {@code ?}. */
    private static final Pattern URL_WITH_QUERY = Pattern.compile("(?:https?://|/)[^?\\s]*\\?.*", Pattern.DOTALL);
    private static final Pattern SIGNING_VALUE = Pattern.compile("X-Amz-(Signature|Credential|Security-Token)=[^&\\s\"]*");
    private static final String QUERY_KEY = "url.query";

    @Bean
    ObservationFilter urlQueryRedaction() {
        return TelemetryRedactionConfig::stripUrlQueries;
    }

    static Observation.Context stripUrlQueries(Observation.Context context) {
        for (KeyValue keyValue : context.getLowCardinalityKeyValues()) {
            KeyValue redacted = redacted(keyValue);
            if (redacted != keyValue) {
                context.addLowCardinalityKeyValue(redacted);
            }
        }
        for (KeyValue keyValue : context.getHighCardinalityKeyValues()) {
            KeyValue redacted = redacted(keyValue);
            if (redacted != keyValue) {
                context.addHighCardinalityKeyValue(redacted);
            }
        }
        context.removeLowCardinalityKeyValue(QUERY_KEY);
        context.removeHighCardinalityKeyValue(QUERY_KEY);
        return context;
    }

    /** {@code keyValue} itself when there is nothing to redact. */
    private static KeyValue redacted(KeyValue keyValue) {
        String value = keyValue.getValue();
        if (URL_WITH_QUERY.matcher(value).matches()) {
            value = value.substring(0, value.indexOf('?'));
        }
        value = SIGNING_VALUE.matcher(value).replaceAll("X-Amz-$1=REDACTED");
        return value.equals(keyValue.getValue()) ? keyValue : KeyValue.of(keyValue.getKey(), value);
    }
}
