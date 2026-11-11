package com.flowcortex.user.phone;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.phone.PhoneChangeStartPolicy.Refusal;
import com.flowcortex.user.phone.PhoneChangeStartPolicy.StartFacts;
import org.junit.jupiter.api.Test;

class PhoneChangeStartPolicyTest {

    @Test
    void aMemberWithNoVerifiedNumberIsRefusedBeforeTheSameNumberIsLookedAt() {
        assertThat(PhoneChangeStartPolicy.firstRefusal(new StartFacts(false, true))).contains(Refusal.NOT_VERIFIED);
        assertThat(PhoneChangeStartPolicy.firstRefusal(new StartFacts(false, false))).contains(Refusal.NOT_VERIFIED);
    }

    @Test
    void theCurrentNumberIsRefusedAsTheSameNumber() {
        assertThat(PhoneChangeStartPolicy.firstRefusal(new StartFacts(true, true))).contains(Refusal.SAME_NUMBER);
    }

    @Test
    void aDifferentNumberIsNotRefused() {
        assertThat(PhoneChangeStartPolicy.firstRefusal(new StartFacts(true, false))).isEmpty();
    }
}
