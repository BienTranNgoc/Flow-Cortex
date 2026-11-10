package com.flowcortex.mail.application;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.mail.api.MailCategory;
import java.util.Set;
import org.junit.jupiter.api.Test;

class LinkSecretRuleTest {

    private static final String SECRET_32 = "0123456789abcdef0123456789abcdef";
    private static final String URL = "https://app.example";

    @Test
    void onlyTransactionalKeysStartWithABlankSecretAndUrl() {
        assertThatCode(() -> LinkSecretRule.check(Set.of(MailCategory.TRANSACTIONAL), "", ""))
                .doesNotThrowAnyException();
        assertThatCode(() -> LinkSecretRule.check(Set.of(MailCategory.TRANSACTIONAL), null, null))
                .doesNotThrowAnyException();
    }

    @Test
    void aCareOrPromotionalKeyNeedsAtLeast32Bytes() {
        for (MailCategory category : Set.of(MailCategory.CARE, MailCategory.PROMOTIONAL)) {
            Set<MailCategory> catalog = Set.of(MailCategory.TRANSACTIONAL, category);

            assertThatThrownBy(() -> LinkSecretRule.check(catalog, SECRET_32.substring(1), URL))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("flowcortex.mail.link-secret");
            assertThatThrownBy(() -> LinkSecretRule.check(catalog, "", URL))
                    .hasMessageContaining("flowcortex.mail.link-secret");
            assertThatCode(() -> LinkSecretRule.check(catalog, SECRET_32, URL)).doesNotThrowAnyException();
        }
    }

    @Test
    void aCareKeyNeedsAPublicUrl() {
        Set<MailCategory> catalog = Set.of(MailCategory.CARE);

        assertThatThrownBy(() -> LinkSecretRule.check(catalog, SECRET_32, " "))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("flowcortex.app.public-url");
        assertThatThrownBy(() -> LinkSecretRule.check(catalog, SECRET_32, null))
                .hasMessageContaining("flowcortex.app.public-url");
    }
}
