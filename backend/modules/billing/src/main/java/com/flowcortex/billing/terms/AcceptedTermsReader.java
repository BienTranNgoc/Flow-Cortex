package com.flowcortex.billing.terms;

import com.flowcortex.billing.terms.AgreementTerms.Chosen;
import com.flowcortex.content.api.PublicationIndex;
import com.flowcortex.content.api.PublicationIndex.PublicationRef;
import com.flowcortex.content.api.PublicationIndex.PublicationText;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/** Reads the payment terms a purchase is under; the tier rule itself is {@link AgreementTerms}. */
@Service
public class AcceptedTermsReader {

    /** The slug of the payment terms document. */
    public static final String SLUG = "legal-payment";

    /** A purchase to find the terms of: its pinned publication (may be null) and when it was opened. */
    public record PurchaseRef(UUID transactionId, UUID pinnedRevisionId, Instant acceptedAt) {
    }

    /** Where a purchase's terms come from; {@code revisionId} is null when the live document is served. */
    public record Provenance(String source, String slug, UUID revisionId, Instant publishedAt) {
    }

    /** The provenance with the terms text. */
    public record AcceptedTerms(Provenance provenance, JsonNode sections, JsonNode seoMeta) {
    }

    private final PublicationIndex publications;

    AcceptedTermsReader(PublicationIndex publications) {
        this.publications = publications;
    }

    /** The newest publication of the payment terms, which is the one in force now; empty when none. */
    public Optional<UUID> currentPublicationId() {
        return publications.publications(SLUG).stream().findFirst().map(PublicationRef::id);
    }

    /**
     * The provenance of each purchase, keyed by transaction id, reading the publications once for all of them.
     *
     * @throws TermsUnavailableException when a purchase has no publication that applies and no live copy exists
     */
    public Map<UUID, Provenance> provenanceOf(List<PurchaseRef> purchases) {
        Map<UUID, Provenance> byPurchase = new HashMap<>();
        if (purchases.isEmpty()) {
            return byPurchase;
        }
        List<PublicationRef> newestFirst = publications.publications(SLUG);
        List<PurchaseRef> unmatched = new ArrayList<>();
        for (PurchaseRef purchase : purchases) {
            Optional<Chosen> chosen = AgreementTerms.choose(newestFirst, purchase.pinnedRevisionId(),
                    purchase.acceptedAt());
            if (chosen.isPresent()) {
                byPurchase.put(purchase.transactionId(), provenance(chosen.get()));
            } else {
                unmatched.add(purchase);
            }
        }
        if (!unmatched.isEmpty()) {
            Provenance fallback = noRecord(publications.live(SLUG).orElseThrow(TermsUnavailableException::new));
            unmatched.forEach(purchase -> byPurchase.put(purchase.transactionId(), fallback));
        }
        return byPurchase;
    }

    /**
     * The terms of one purchase with their text.
     *
     * @throws TermsUnavailableException when nothing can be served
     */
    public AcceptedTerms resolve(UUID pinned, Instant acceptedAt) {
        Optional<Chosen> chosen = AgreementTerms.choose(publications.publications(SLUG), pinned, acceptedAt);
        if (chosen.isPresent()) {
            PublicationText text = publications.publication(SLUG, chosen.get().ref().id())
                    .orElseThrow(TermsUnavailableException::new);
            return new AcceptedTerms(provenance(chosen.get()), text.sections(), text.seoMeta());
        }
        PublicationText live = publications.live(SLUG).orElseThrow(TermsUnavailableException::new);
        return new AcceptedTerms(noRecord(live), live.sections(), live.seoMeta());
    }

    private static Provenance provenance(Chosen chosen) {
        return new Provenance(chosen.source(), SLUG, chosen.ref().id(), chosen.ref().publishedAt());
    }

    private static Provenance noRecord(PublicationText live) {
        return new Provenance(AgreementTerms.NO_RECORD, SLUG, null, live.publishedAt());
    }
}
