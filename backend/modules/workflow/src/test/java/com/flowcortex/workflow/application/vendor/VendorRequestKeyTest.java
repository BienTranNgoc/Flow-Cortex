package com.flowcortex.workflow.application.vendor;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class VendorRequestKeyTest {

    private static final String CAPABILITY = "generate_video";
    private static final String PROFILE = "vertical_ad_clip_v1";

    @Test
    void payloadsDifferingOnlyInTheSourceRevisionShareAKey() {
        String first = VendorRequestKey.of(CAPABILITY, PROFILE, Map.of("prompt", "a cat", "sourceRevisionId", "r1"));
        String second = VendorRequestKey.of(CAPABILITY, PROFILE, Map.of("prompt", "a cat", "sourceRevisionId", "r2"));

        assertThat(first).isEqualTo(second);
    }

    @Test
    void keyOrderDoesNotChangeTheKey() {
        Map<String, Object> promptFirst = new LinkedHashMap<>();
        promptFirst.put("prompt", "a cat");
        promptFirst.put("x", Map.of("b", 1, "a", 2));
        Map<String, Object> promptLast = new LinkedHashMap<>();
        promptLast.put("x", new LinkedHashMap<>(Map.of("a", 2, "b", 1)));
        promptLast.put("prompt", "a cat");

        assertThat(VendorRequestKey.of(CAPABILITY, PROFILE, promptFirst))
                .isEqualTo(VendorRequestKey.of(CAPABILITY, PROFILE, promptLast));
    }

    @Test
    void aDifferentPromptOrProfileChangesTheKey() {
        String base = VendorRequestKey.of(CAPABILITY, PROFILE, Map.of("prompt", "a cat"));

        assertThat(VendorRequestKey.of(CAPABILITY, PROFILE, Map.of("prompt", "a dog"))).isNotEqualTo(base);
        assertThat(VendorRequestKey.of(CAPABILITY, "other_profile", Map.of("prompt", "a cat"))).isNotEqualTo(base);
    }

    @Test
    void theKeyIsSixtyFourLowercaseHexCharacters() {
        assertThat(VendorRequestKey.of(CAPABILITY, PROFILE, Map.of("prompt", "a cat"))).matches("[0-9a-f]{64}");
    }
}
