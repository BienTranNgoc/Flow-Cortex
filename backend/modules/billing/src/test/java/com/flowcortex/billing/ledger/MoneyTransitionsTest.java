package com.flowcortex.billing.ledger;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTransitionsTest {

    @ParameterizedTest
    @CsvSource({
        "PENDING, SUCCEEDED",
        "PENDING, FAILED",
        "PENDING, EXPIRED",
        "SUCCEEDED, PARTIALLY_REFUNDED",
        "SUCCEEDED, REFUNDED",
        "SUCCEEDED, DISPUTED",
        "PARTIALLY_REFUNDED, REFUNDED",
        "PARTIALLY_REFUNDED, DISPUTED",
        "REFUNDED, DISPUTED",
        "EXPIRED, SUCCEEDED"
    })
    void everyPermittedMoveIsApplied(MoneyStatus from, MoneyStatus to) {
        assertThat(MoneyTransitions.evaluate(from, to)).isEqualTo(new MoneyTransitions.Apply(from));
    }

    @ParameterizedTest
    @EnumSource(MoneyStatus.class)
    void aMoveToTheCurrentStatusHasNothingToDo(MoneyStatus status) {
        assertThat(MoneyTransitions.evaluate(status, status)).isEqualTo(new MoneyTransitions.Noop());
    }

    @ParameterizedTest
    @CsvSource({
        "SUCCEEDED, PENDING",
        "REFUNDED, SUCCEEDED",
        "DISPUTED, REFUNDED",
        "FAILED, PENDING",
        "PARTIALLY_REFUNDED, EXPIRED"
    })
    void aMoveToALowerRankIsRefusedAsBackward(MoneyStatus from, MoneyStatus to) {
        assertThat(MoneyTransitions.evaluate(from, to))
                .isEqualTo(new MoneyTransitions.Refused("backward_transition"));
    }

    @ParameterizedTest
    @CsvSource({
        "FAILED, SUCCEEDED",
        "EXPIRED, FAILED",
        "PENDING, REFUNDED",
        "FAILED, EXPIRED",
        "SUCCEEDED, FAILED",
        "PENDING, DISPUTED"
    })
    void anyOtherMoveIsRefusedAsUndeclared(MoneyStatus from, MoneyStatus to) {
        assertThat(MoneyTransitions.evaluate(from, to))
                .isEqualTo(new MoneyTransitions.Refused("undeclared_transition"));
    }

    @Test
    void theDeclaredTableGoesUpTheRankExceptForTheLateSuccessAfterExpiry() {
        Map<MoneyStatus, Set<MoneyStatus>> table = MoneyTransitions.permittedMoves();

        assertThat(table).isNotEmpty();
        table.forEach((from, targets) -> targets.forEach(to -> {
            boolean lateSuccess = from == MoneyStatus.EXPIRED && to == MoneyStatus.SUCCEEDED;
            if (lateSuccess) {
                assertThat(to.rank()).isEqualTo(from.rank());
            } else {
                assertThat(to.rank()).as("%s -> %s", from, to).isGreaterThan(from.rank());
            }
        }));
    }

    @Test
    void ranksFollowTheMoneyLifecycle() {
        assertThat(MoneyStatus.PENDING.rank()).isEqualTo(0);
        assertThat(MoneyStatus.SUCCEEDED.rank()).isEqualTo(1);
        assertThat(MoneyStatus.FAILED.rank()).isEqualTo(1);
        assertThat(MoneyStatus.EXPIRED.rank()).isEqualTo(1);
        assertThat(MoneyStatus.PARTIALLY_REFUNDED.rank()).isEqualTo(2);
        assertThat(MoneyStatus.REFUNDED.rank()).isEqualTo(3);
        assertThat(MoneyStatus.DISPUTED.rank()).isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(MoneyStatus.class)
    void aStatusRoundTripsThroughItsWireValue(MoneyStatus status) {
        assertThat(MoneyStatus.fromWire(status.wire())).contains(status);
    }

    @Test
    void wireValuesAreTheLowerSnakeCaseNamesTheDatabaseStores() {
        assertThat(MoneyStatus.PARTIALLY_REFUNDED.wire()).isEqualTo("partially_refunded");
        assertThat(MoneyStatus.SUCCEEDED.wire()).isEqualTo("succeeded");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "cancelled", "SUCCEEDED", "Pending"})
    void aValueOutsideTheSevenStatusesReadsAsNothing(String wire) {
        assertThat(MoneyStatus.fromWire(wire)).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(value = MoneyStatus.class, names = {"SUCCEEDED", "PARTIALLY_REFUNDED", "REFUNDED"})
    void successThatMoneyReachedIsSettled(MoneyStatus status) {
        assertThat(status.isSettled()).isTrue();
    }

    @ParameterizedTest
    @EnumSource(value = MoneyStatus.class, names = {"PENDING", "FAILED", "EXPIRED", "DISPUTED"})
    void everyOtherStatusIsNotSettled(MoneyStatus status) {
        assertThat(status.isSettled()).isFalse();
    }
}
