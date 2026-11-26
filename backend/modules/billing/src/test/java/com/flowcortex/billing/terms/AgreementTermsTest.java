package com.flowcortex.billing.terms;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.billing.terms.AgreementTerms.Chosen;
import com.flowcortex.content.api.PublicationIndex.PublicationRef;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AgreementTermsTest {

    private static final PublicationRef P3 = new PublicationRef(UUID.randomUUID(), Instant.parse("2026-11-20T00:00:00Z"));
    private static final PublicationRef P2 = new PublicationRef(UUID.randomUUID(), Instant.parse("2026-10-01T00:00:00Z"));
    private static final PublicationRef P1 = new PublicationRef(UUID.randomUUID(), Instant.parse("2026-09-01T00:00:00Z"));
    private static final List<PublicationRef> NEWEST_FIRST = List.of(P3, P2, P1);

    @Test
    void aPinnedPublicationThatStillExistsIsRecorded() {
        Optional<Chosen> chosen = AgreementTerms.choose(NEWEST_FIRST, P2.id(), Instant.parse("2026-11-25T00:00:00Z"));

        assertThat(chosen).contains(new Chosen(P2, "recorded"));
    }

    @Test
    void aPinToAnUnknownPublicationFallsThroughToTheOneInForceWhenAccepted() {
        Optional<Chosen> chosen = AgreementTerms.choose(NEWEST_FIRST, UUID.randomUUID(),
                Instant.parse("2026-10-15T00:00:00Z"));

        assertThat(chosen).contains(new Chosen(P2, "reconstructed"));
    }

    @Test
    void noPinChoosesTheNewestPublicationAtOrBeforeAcceptance() {
        Optional<Chosen> chosen = AgreementTerms.choose(NEWEST_FIRST, null, Instant.parse("2026-11-20T00:00:00Z"));

        assertThat(chosen).contains(new Chosen(P3, "reconstructed"));
    }

    @Test
    void acceptanceBeforeEveryPublicationChoosesNothing() {
        assertThat(AgreementTerms.choose(NEWEST_FIRST, null, Instant.parse("2026-08-01T00:00:00Z"))).isEmpty();
    }

    @Test
    void noPublicationsChoosesNothing() {
        assertThat(AgreementTerms.choose(List.of(), UUID.randomUUID(), Instant.parse("2026-11-25T00:00:00Z"))).isEmpty();
    }
}
