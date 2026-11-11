package com.flowcortex.mail.links;

import com.flowcortex.mail.api.MailKey;
import com.flowcortex.mail.api.MailRecipientDirectory;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Mints the unsubscribe links of care and promotional mail and serves the public routes that view and
 * apply them. A token that does not verify, names no row, or names a member who no longer exists is
 * always the same empty answer.
 */
@Component
public class UnsubscribeLinks {

    /** A valid link as opened: nothing has been written. */
    public record Viewed(UUID memberId, LinkList list, boolean unsubscribed, String maskedEmail) {
    }

    /** A valid link after pressing it; {@code changed} is false when the member had already left the list. */
    public record Applied(UUID memberId, LinkList list, boolean changed, String maskedEmail) {
    }

    private final UnsubscribeTokens tokens;
    private final UnsubscribeLinkRepository repository;
    private final MailRecipientDirectory recipients;
    private final String publicUrl;

    UnsubscribeLinks(UnsubscribeTokens tokens, UnsubscribeLinkRepository repository,
            MailRecipientDirectory recipients, @Value("${flowcortex.app.public-url:}") String publicUrl) {
        this.tokens = tokens;
        this.repository = repository;
        this.recipients = recipients;
        String url = publicUrl == null ? "" : publicUrl.strip();
        this.publicUrl = url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    /** Which lists the member has left; a member with no link row has left none. */
    public LinkState state(UUID memberId) {
        return repository.state(memberId);
    }

    /** The footer and one-click URLs for a mail of {@code key}, creating the member's link row on first use. */
    public UnsubscribeLink forSend(UUID memberId, LinkList list, MailKey key) {
        String token = tokens.mint(repository.ensureLinkId(memberId), list);
        String src = "?src=" + key.id();
        return new UnsubscribeLink(publicUrl + "/email/unsubscribe/" + token + src,
                publicUrl + "/api/v1/email-links/" + token + "/one-click" + src);
    }

    /** Reads a link without changing anything. */
    public Optional<Viewed> view(String token) {
        return resolve(token).map(link -> new Viewed(link.row().memberId(), link.list(),
                link.row().state().unsubscribedFrom(link.list()), link.maskedEmail()));
    }

    /**
     * Unsubscribes the member from the token's list. {@code src} is stored only when it names a catalog key.
     */
    public Optional<Applied> apply(String token, LinkSource source, String src) {
        return resolve(token).map(link -> {
            String mailKey = src == null ? null : MailKey.fromId(src).map(MailKey::id).orElse(null);
            boolean changed = repository.apply(link.row().id(), link.row().memberId(), link.list(), source, mailKey);
            return new Applied(link.row().memberId(), link.list(), changed, link.maskedEmail());
        });
    }

    private Optional<Resolved> resolve(String token) {
        return tokens.verify(token)
                .flatMap(verified -> repository.find(verified.linkId())
                        .flatMap(row -> recipients.emailOf(row.memberId())
                                .map(email -> new Resolved(row, verified.list(), MaskedEmail.of(email)))));
    }

    private record Resolved(UnsubscribeLinkRepository.LinkRow row, LinkList list, String maskedEmail) {
    }
}
