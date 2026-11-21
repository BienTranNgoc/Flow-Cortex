package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class AccessorKeysTest {

    private static final String SECRET = "workflow-share-hash-secret-for-unit-tests-0123456789";

    private final AccessorKeys keys = new AccessorKeys(SECRET);

    @Test
    void oneAddressAndAgentGiveOneKey() {
        String key = keys.anonymous("203.0.113.7", "Mozilla/5.0");

        assertThat(keys.anonymous("203.0.113.7", "Mozilla/5.0")).isEqualTo(key);
        assertThat(key).matches("n:[0-9a-f]{64}");
    }

    @Test
    void twoAddressesInOneMaskedNetworkGiveTwoKeys() {
        assertThat(keys.anonymous("203.0.113.7", "Mozilla/5.0"))
                .isNotEqualTo(keys.anonymous("203.0.200.9", "Mozilla/5.0"));
    }

    @Test
    void twoAgentsOnOneAddressGiveTwoKeys() {
        assertThat(keys.anonymous("203.0.113.7", "Mozilla/5.0"))
                .isNotEqualTo(keys.anonymous("203.0.113.7", "curl/8.0"));
    }

    @Test
    void theKeyHoldsNoPartOfTheAddress() {
        String key = keys.anonymous("203.0.113.7", "Mozilla/5.0");

        assertThat(key).doesNotContain("203.0").doesNotContain("113.7");
    }

    @Test
    void aMissingAgentStillGivesAKey() {
        assertThat(keys.anonymous("203.0.113.7", null)).matches("n:[0-9a-f]{64}");
        assertThat(keys.anonymous(null, null)).matches("n:[0-9a-f]{64}");
    }

    @Test
    void aBlankOrShortSecretFailsConstruction() {
        assertThatThrownBy(() -> new AccessorKeys("")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccessorKeys(null)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new AccessorKeys("short-secret")).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FLOWCORTEX_WORKFLOW_SHARE_HASH_SECRET");
    }
}
