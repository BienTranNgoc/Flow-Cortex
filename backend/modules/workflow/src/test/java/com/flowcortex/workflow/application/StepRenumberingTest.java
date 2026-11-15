package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.workflow.application.StepRenumbering.Duplicate;
import com.flowcortex.workflow.application.StepRenumbering.ForwardReference;
import com.flowcortex.workflow.application.StepRenumbering.Outcome;
import com.flowcortex.workflow.application.StepRenumbering.Plan;
import com.flowcortex.workflow.application.StepRenumbering.Planned;
import com.flowcortex.workflow.application.StepRenumbering.Referenced;
import com.flowcortex.workflow.application.StepRenumbering.Row;
import com.flowcortex.workflow.application.StepRenumbering.Stale;
import com.flowcortex.workflow.application.StepRenumbering.Unchanged;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class StepRenumberingTest {

    private static final String REMOTE_CONFIG_34 =
            "{\"capability\":\"x\",\"outputInputs\":[{\"stepOrder\":3,\"outputName\":\"a\",\"inputKey\":\"k1\"},"
                    + "{\"stepOrder\":4,\"outputName\":\"b\",\"inputKey\":\"k2\",\"optional\":true}]}";

    private static Row row(int order, String inputRef, String config) {
        return new Row(UUID.randomUUID(), order, "echo", "S" + order, inputRef, config);
    }

    private static Row row(int order) {
        return row(order, null, null);
    }

    private static Plan plan(Outcome outcome) {
        assertThat(outcome).isInstanceOf(Plan.class);
        return (Plan) outcome;
    }

    private static List<UUID> ids(List<Row> rows, int... orders) {
        List<UUID> result = new ArrayList<>();
        for (int order : orders) {
            result.add(rows.get(order - 1).id());
        }
        return result;
    }

    @Test
    void deletingAMiddleStepShiftsLaterOrdersAndTheirReferencesDownByOne() {
        List<Row> rows = List.of(row(1), row(2), row(3), row(4, "{step:3.script}", null), row(5, null, REMOTE_CONFIG_34));

        Plan plan = plan(StepRenumbering.forDelete(rows, rows.get(1).id()));

        assertThat(plan.rows()).extracting(p -> p.row().order()).containsExactly(1, 2, 3, 4);
        Planned former4 = plan.rows().get(2);
        assertThat(former4.row().inputRef()).isEqualTo("{step:2.script}");
        assertThat(former4.inputRefChanged()).isTrue();
        Planned former5 = plan.rows().get(3);
        assertThat(former5.row().config())
                .contains("\"stepOrder\":2", "\"stepOrder\":3", "\"capability\":\"x\"", "\"optional\":true");
        assertThat(former5.configChanged()).isTrue();
        assertThat(plan.rows().get(0).orderChanged()).isFalse();
        assertThat(plan.rows().get(1).orderChanged()).isTrue();
    }

    @Test
    void referencesToEarlierStepsAndUnrecognisedInputRefTextStayAsStored() {
        List<Row> rows = List.of(row(1), row(2), row(3, "{step: 2}", null), row(4, "{step:1}", null));

        Plan plan = plan(StepRenumbering.forDelete(rows, rows.get(1).id()));

        assertThat(plan.rows().get(1).row().inputRef()).isEqualTo("{step: 2}");
        assertThat(plan.rows().get(1).referencesChanged()).isFalse();
        assertThat(plan.rows().get(2).row().inputRef()).isEqualTo("{step:1}");
    }

    @Test
    void deletingAStepReadByAnInputRefOrAnOutputInputsEntryIsRefusedNamingTheReaders() {
        List<Row> rows = List.of(row(1), row(2), row(3, null, "{\"outputInputs\":[{\"stepOrder\":2}]}"),
                row(4, "{step:2.text}", null));

        Outcome outcome = StepRenumbering.forDelete(rows, rows.get(1).id());

        assertThat(outcome).isEqualTo(new Referenced(2, List.of(3, 4)));
    }

    @Test
    void aStepWithNoInputRefDoesNotBlockTheDelete() {
        List<Row> rows = List.of(row(1), row(2), row(3));

        Plan plan = plan(StepRenumbering.forDelete(rows, rows.get(1).id()));

        assertThat(plan.rows()).extracting(p -> p.row().inputRef()).containsOnlyNulls();
        assertThat(plan.rows()).extracting(p -> p.row().order()).containsExactly(1, 2);
    }

    @Test
    void deletingTheLastStepLeavesTheOthersUntouched() {
        List<Row> rows = List.of(row(1), row(2, "{step:1}", null), row(3));

        Plan plan = plan(StepRenumbering.forDelete(rows, rows.get(2).id()));

        assertThat(plan.rows()).noneMatch(Planned::orderChanged);
        assertThat(plan.rows()).noneMatch(Planned::referencesChanged);
    }

    @Test
    void deletingTheOnlyStepLeavesAnEmptyPlan() {
        List<Row> rows = List.of(row(1));

        assertThat(plan(StepRenumbering.forDelete(rows, rows.get(0).id())).rows()).isEmpty();
    }

    @Test
    void aReferenceToNoCurrentStepIsLeftAsStoredAndDoesNotBlock() {
        List<Row> rows = List.of(row(1), row(2, "{step:5.output}", "{\"outputInputs\":[{\"stepOrder\":9}]}"));

        Plan afterDelete = plan(StepRenumbering.forDelete(rows, rows.get(0).id()));
        assertThat(afterDelete.rows().get(0).row().inputRef()).isEqualTo("{step:5.output}");
        assertThat(afterDelete.rows().get(0).row().config()).contains("\"stepOrder\":9");
        assertThat(afterDelete.rows().get(0).referencesChanged()).isFalse();

        Plan afterReorder = plan(StepRenumbering.forOrder(rows, ids(rows, 2, 1)));
        assertThat(afterReorder.rows().get(0).row().inputRef()).isEqualTo("{step:5.output}");
        assertThat(afterReorder.rows().get(0).referencesChanged()).isFalse();
    }

    @Test
    void aReferenceTooLargeForAnIntIsLeftAsStored() {
        List<Row> rows = List.of(row(1), row(2, "{step:99999999999999999999}", null));

        Plan plan = plan(StepRenumbering.forDelete(rows, rows.get(0).id()));

        assertThat(plan.rows().get(0).row().inputRef()).isEqualTo("{step:99999999999999999999}");
    }

    @Test
    void aReorderMovesEveryExplicitReferenceWithItsStep() {
        List<Row> rows = List.of(row(1), row(2), row(3, "{step:1.title}", null),
                row(4, null, "{\"outputInputs\":[{\"stepOrder\":2,\"outputName\":\"o\",\"inputKey\":\"k\"}]}"));

        Plan plan = plan(StepRenumbering.forOrder(rows, ids(rows, 2, 1, 3, 4)));

        assertThat(plan.rows()).extracting(p -> p.row().id()).containsExactlyElementsOf(ids(rows, 2, 1, 3, 4));
        assertThat(plan.rows().get(2).row().inputRef()).isEqualTo("{step:2.title}");
        assertThat(plan.rows().get(3).row().config()).contains("\"stepOrder\":1");
        assertThat(plan.rows().get(0).oldOrder()).isEqualTo(2);
    }

    @Test
    void aReorderThatPutsAReaderAtOrBeforeItsSourceIsRefusedAtTheReadersNewIndex() {
        List<Row> rows = List.of(row(1), row(2, "{step:1}", null));

        assertThat(StepRenumbering.forOrder(rows, ids(rows, 2, 1))).isEqualTo(new ForwardReference(List.of(0)));

        List<Row> three = List.of(row(1), row(2, "{step:1}", null), row(3));
        Plan ok = plan(StepRenumbering.forOrder(three, ids(three, 3, 1, 2)));
        assertThat(ok.rows().get(2).row().inputRef()).isEqualTo("{step:2}");
    }

    @Test
    void aReaderMovedToOrderOneIsRefusedEvenWhenItsSourceMovesToo() {
        List<Row> rows = List.of(row(1), row(2), row(3, "{step:2}", null));

        assertThat(StepRenumbering.forOrder(rows, ids(rows, 3, 2, 1))).isEqualTo(new ForwardReference(List.of(0)));
    }

    @Test
    void theCurrentOrderIsUnchanged() {
        List<Row> rows = List.of(row(1), row(2), row(3));

        assertThat(StepRenumbering.forOrder(rows, ids(rows, 1, 2, 3))).isEqualTo(new Unchanged());
    }

    @Test
    void aRepeatedIdIsDuplicate() {
        List<Row> rows = List.of(row(1), row(2), row(3));
        List<UUID> repeated = List.of(rows.get(0).id(), rows.get(0).id(), rows.get(1).id());

        assertThat(StepRenumbering.forOrder(rows, repeated)).isEqualTo(new Duplicate());
    }

    @Test
    void aMissingOrForeignIdIsStale() {
        List<Row> rows = List.of(row(1), row(2), row(3));

        assertThat(StepRenumbering.forOrder(rows, ids(rows, 2, 1))).isEqualTo(new Stale());
        assertThat(StepRenumbering.forOrder(rows, List.of(rows.get(0).id(), rows.get(1).id(), UUID.randomUUID())))
                .isEqualTo(new Stale());
        assertThat(StepRenumbering.forDelete(rows, UUID.randomUUID())).isEqualTo(new Stale());
    }
}
