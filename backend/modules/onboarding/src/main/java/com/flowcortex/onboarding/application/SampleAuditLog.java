package com.flowcortex.onboarding.application;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The one line every admin write to the onboarding sample leaves, whichever layer refused or applied
 * it. Callers pass only ids, enum values and fixed reason codes, never request text.
 */
public final class SampleAuditLog {

    private static final Logger LOG = LoggerFactory.getLogger("com.flowcortex.onboarding.sample");
    private static final Set<String> LOCALES = Set.of("vi", "en");

    /** How an admin write ended. */
    public enum Outcome {
        SAVED, CLEARED, REFUSED;

        String text() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    private SampleAuditLog() {
    }

    /** A {@code PUT} of the sample itself. */
    public static void sample(Outcome outcome, UUID adminId, UUID workflowId, String reason) {
        LOG.info("onboarding.sample outcome={} adminId={} workflowId={} reason={}", outcome.text(), adminId,
                workflowId, reason);
    }

    /** A {@code PUT} of one language's translation; a locale outside the shipped ones is logged as null. */
    public static void translation(Outcome outcome, UUID adminId, String locale, String reason) {
        LOG.info("onboarding.sample.translation outcome={} adminId={} locale={} reason={}", outcome.text(), adminId,
                LOCALES.contains(locale) ? locale : null, reason);
    }
}
