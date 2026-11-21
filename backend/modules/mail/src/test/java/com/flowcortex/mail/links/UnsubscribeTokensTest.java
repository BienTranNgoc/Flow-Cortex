package com.flowcortex.mail.links;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class UnsubscribeTokensTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef";

    private final UnsubscribeTokens tokens = new UnsubscribeTokens(SECRET);
    private final UUID linkId = UUID.randomUUID();

    @Test
    void aMintedTokenVerifiesToItsLinkAndList() {
        for (LinkList list : LinkList.values()) {
            String token = tokens.mint(linkId, list);

            assertThat(tokens.verify(token)).contains(new UnsubscribeTokens.Verified(linkId, list));
        }
    }

    @Test
    void aTamperedSignatureIsRejected() {
        String token = tokens.mint(linkId, LinkList.CARE);
        char last = token.charAt(token.length() - 1);
        String tampered = token.substring(0, token.length() - 1) + (last == 'A' ? 'B' : 'A');

        assertThat(tokens.verify(tampered)).isEmpty();
        assertThat(tokens.verify(token + "A")).isEmpty();
    }

    @Test
    void aTokenSignedUnderAnotherSecretIsRejected() {
        String foreign = new UnsubscribeTokens("fedcba9876543210fedcba9876543210").mint(linkId, LinkList.MARKETING);

        assertThat(tokens.verify(foreign)).isEmpty();
    }

    @Test
    void noSeparatorOrANonUuidLinkIdIsRejected() {
        String token = tokens.mint(linkId, LinkList.CARE);
        String signature = token.substring(token.indexOf('.') + 1);

        assertThat(tokens.verify(token.replace('.', '_'))).isEmpty();
        assertThat(tokens.verify("not-a-uuid." + signature)).isEmpty();
        assertThat(tokens.verify("1-1-1-1-1." + signature)).isEmpty();
        assertThat(tokens.verify("." + signature)).isEmpty();
        assertThat(tokens.verify(linkId + ".")).isEmpty();
        assertThat(tokens.verify("")).isEmpty();
        assertThat(tokens.verify(null)).isEmpty();
        assertThat(tokens.verify(linkId + ".%%%")).isEmpty();
    }

    @Test
    void aBlankSecretVerifiesNothing() {
        UnsubscribeTokens unconfigured = new UnsubscribeTokens("");
        String token = tokens.mint(linkId, LinkList.CARE);

        assertThat(unconfigured.verify(token)).isEmpty();
        assertThat(unconfigured.verify(linkId + ".")).isEmpty();
    }
}
