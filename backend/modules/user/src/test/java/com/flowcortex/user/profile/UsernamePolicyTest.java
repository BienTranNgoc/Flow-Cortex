package com.flowcortex.user.profile;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.profile.UsernamePolicy.Change;
import com.flowcortex.user.profile.UsernamePolicy.Decision;
import com.flowcortex.user.profile.UsernamePolicy.Refusal;
import com.flowcortex.user.profile.UsernamePolicy.TextVerdict;
import com.flowcortex.user.profile.UsernameTerms.Entry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class UsernamePolicyTest {

    private static final Instant NOW = Instant.parse("2026-11-20T08:00:00Z");

    private static Decision decide(String current, String next, Instant changedAt) {
        return UsernamePolicy.decide(new Change(current, next, changedAt, NOW));
    }

    private static void assertBlocked(String name) {
        assertThat(UsernamePolicy.classify(name)).as(name).isInstanceOf(TextVerdict.Blocked.class);
    }

    @Test
    void reservedGroupsBlockOnBothCollapseLevels() {
        for (String name : List.of("admin", "a_d_m_i_n", "adminnn", "admin99", "ssssladmin")) {
            assertBlocked(name);
        }
    }

    @Test
    void allowlistExemptsR3Only() {
        Function<String, TextVerdict> classifier = UsernamePolicy.classifier(
                List.of(new Entry("R3", "CONTAINS", "bad"), new Entry("R1", "EXACT", "root")),
                List.of("badminton", "root"));

        assertThat(classifier.apply("badminton")).isEqualTo(new TextVerdict.Ok());
        assertThat(classifier.apply("root")).isEqualTo(new TextVerdict.Blocked("R1"));
        assertThat(classifier.apply("badguy")).isEqualTo(new TextVerdict.Blocked("R3"));
    }

    @Test
    void containsTermBlocksAnywhere() {
        assertBlocked("my_ditme_name");
        assertBlocked("xxnodixx");
    }

    @Test
    void trailingDigitsStrippedForExactMatch() {
        assertBlocked("vtv1234");
        assertBlocked("support7");
        assertThat(UsernamePolicy.classify("an_1")).isEqualTo(new TextVerdict.Ok());
    }

    @Test
    void sameNameIsNoopBeforeAnyOtherRule() {
        assertThat(decide("An_1", "an_1", NOW.minusSeconds(60))).isEqualTo(new Decision.Noop());
        assertThat(decide("admin", "ADMIN", null)).isEqualTo(new Decision.Noop());
    }

    @Test
    void cooldownRefusesInsideThirtyDaysAndNamesTheUnlockInstant() {
        Instant changedAt = Instant.parse("2026-11-10T08:00:00Z");

        assertThat(decide("an_2", "an_3", changedAt))
                .isEqualTo(new Decision.Refuse(Refusal.CHANGE_TOO_SOON, Instant.parse("2026-12-10T08:00:00Z"), null));
    }

    @Test
    void cooldownEndsAfterExactlyThirtyDays() {
        Instant exactly = NOW.minus(Duration.ofDays(30));

        assertThat(decide("an_2", "an_3", exactly)).isEqualTo(new Decision.Proceed(true, true));
        assertThat(decide("an_2", "an_3", exactly.plusSeconds(1))).isInstanceOf(Decision.Refuse.class);
        assertThat(decide("an_2", "an_3", exactly.minusSeconds(1))).isEqualTo(new Decision.Proceed(true, true));
    }

    @Test
    void cooldownAnswersBeforeTheBlocklist() {
        Decision decision = decide("an_2", "admin", NOW.minus(Duration.ofDays(1)));

        assertThat(decision).isInstanceOf(Decision.Refuse.class);
        assertThat(((Decision.Refuse) decision).code()).isEqualTo(Refusal.CHANGE_TOO_SOON);
    }

    @Test
    void firstUsernameIsFreeAndHoldsNothing() {
        assertThat(decide(null, "an_1", null)).isEqualTo(new Decision.Proceed(false, false));
    }

    @Test
    void changeFromAStoredNameCountsAndHoldsTheOldName() {
        assertThat(decide("an_1", "an_2", null)).isEqualTo(new Decision.Proceed(true, true));
    }

    @Test
    void everyBlockedGroupGivesTheSameCode() {
        for (String name : List.of("admin", "nodi", "hochiminh", "momo", "fuck", "vtv")) {
            Decision decision = decide("an_1", name, null);
            assertThat(decision).as(name).isInstanceOf(Decision.Refuse.class);
            assertThat(((Decision.Refuse) decision).code()).as(name).isEqualTo(Refusal.NOT_ALLOWED);
            assertThat(((Decision.Refuse) decision).nextChangeAt()).as(name).isNull();
        }
    }

    @Test
    void brandGroupBlocksNodiAndFlowcortex() {
        assertBlocked("nodi");
        assertBlocked("flowcortex");
        assertBlocked("the_flowcortex_team");
    }
}
