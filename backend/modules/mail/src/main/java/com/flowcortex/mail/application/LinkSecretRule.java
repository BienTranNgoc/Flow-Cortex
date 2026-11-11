package com.flowcortex.mail.application;

import com.flowcortex.mail.api.MailCategory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Startup rule: once the catalog holds a care or promotional key, a link can be minted, so the signing
 * secret and the public URL it points at must exist.
 */
final class LinkSecretRule {

    static final int MIN_SECRET_BYTES = 32;

    private LinkSecretRule() {
    }

    static void check(Set<MailCategory> categoriesInCatalog, String secret, String publicUrl) {
        if (categoriesInCatalog.stream().noneMatch(category -> category != MailCategory.TRANSACTIONAL)) {
            return;
        }
        List<String> missing = new ArrayList<>();
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            missing.add("flowcortex.mail.link-secret (FLOWCORTEX_MAIL_LINK_SECRET) must be at least "
                    + MIN_SECRET_BYTES + " bytes");
        }
        if (publicUrl == null || publicUrl.isBlank()) {
            missing.add("flowcortex.app.public-url (FLOWCORTEX_APP_PUBLIC_URL) must be set, the member app origin "
                    + "the unsubscribe links point at");
        }
        if (!missing.isEmpty()) {
            throw new IllegalStateException("The mail catalog holds care or promotional mail, so unsubscribe links "
                    + "are signed: " + String.join("; ", missing));
        }
    }
}
