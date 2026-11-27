package com.flowcortex.billing.terms;

import com.flowcortex.content.api.PublicationIndex.PublicationRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The one rule that decides which publication of the payment terms a purchase is under: the one it pinned when
 * it was opened while that publication still exists, else the newest published at or before the opening time.
 * Where neither exists the caller falls back to the document published now.
 */
public final class AgreementTerms {

    public static final String RECORDED = "recorded";
    public static final String RECONSTRUCTED = "reconstructed";
    public static final String NO_RECORD = "no_record";

    /** The chosen publication and whether it was recorded at opening or worked out afterwards. */
    public record Chosen(PublicationRef ref, String source) {
    }

    private AgreementTerms() {
    }

    /** {@code newestFirst} is the document's publications, newest first. */
    public static Optional<Chosen> choose(List<PublicationRef> newestFirst, UUID pinned, Instant acceptedAt) {
        if (pinned != null) {
            Optional<PublicationRef> recorded = newestFirst.stream().filter(ref -> ref.id().equals(pinned)).findFirst();
            if (recorded.isPresent()) {
                return Optional.of(new Chosen(recorded.get(), RECORDED));
            }
        }
        return newestFirst.stream()
                .filter(ref -> !ref.publishedAt().isAfter(acceptedAt))
                .findFirst()
                .map(ref -> new Chosen(ref, RECONSTRUCTED));
    }
}
