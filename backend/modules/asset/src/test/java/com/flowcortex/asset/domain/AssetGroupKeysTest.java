package com.flowcortex.asset.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.flowcortex.asset.domain.AssetGroupKeys.Day;
import com.flowcortex.asset.domain.AssetGroupKeys.GroupBy;
import com.flowcortex.asset.domain.AssetGroupKeys.Upload;
import com.flowcortex.asset.domain.AssetGroupKeys.Workflow;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AssetGroupKeysTest {

    private static final UUID WORKFLOW = UUID.fromString("0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70");
    private static final Instant CREATED = Instant.parse("2026-11-14T17:30:00Z");

    @Test
    void runAssetWithWorkflowIsWorkflowKey() {
        assertThat(AssetGroupKeys.keyOf(GroupBy.WORKFLOW, "run", WORKFLOW, CREATED))
                .isEqualTo("workflow:0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70");
    }

    @Test
    void uploadOrRunWithoutWorkflowIsUploadKey() {
        assertThat(AssetGroupKeys.keyOf(GroupBy.WORKFLOW, "upload", WORKFLOW, CREATED)).isEqualTo("upload");
        assertThat(AssetGroupKeys.keyOf(GroupBy.WORKFLOW, "upload", null, CREATED)).isEqualTo("upload");
        assertThat(AssetGroupKeys.keyOf(GroupBy.WORKFLOW, "run", null, CREATED)).isEqualTo("upload");
    }

    @Test
    void dayKeyUsesHoChiMinhTime() {
        assertThat(AssetGroupKeys.keyOf(GroupBy.DAY, "run", WORKFLOW, CREATED)).isEqualTo("day:2026-11-15");
        assertThat(AssetGroupKeys.keyOf(GroupBy.DAY, "upload", null, Instant.parse("2026-11-14T16:59:59Z")))
                .isEqualTo("day:2026-11-14");
    }

    @Test
    void parsesEachKeyBackToItsScope() {
        assertThat(AssetGroupKeys.parse("workflow:0B9F6E2C-7D1E-4A3B-9C55-2F6A8D4E1B70")).isEqualTo(new Workflow(WORKFLOW));
        assertThat(AssetGroupKeys.parse("upload")).isEqualTo(new Upload());
        assertThat(AssetGroupKeys.parse("day:2026-11-15")).isEqualTo(new Day(LocalDate.of(2026, 11, 15)));

        for (String key : new String[] {"workflow:0b9f6e2c-7d1e-4a3b-9c55-2f6a8d4e1b70", "upload", "day:2026-11-15"}) {
            assertThat(AssetGroupKeys.parse(key).key()).isEqualTo(key);
        }
    }

    @Test
    void dayScopeCoversTheHoChiMinhDayAsAHalfOpenRange() {
        Day day = new Day(LocalDate.of(2026, 11, 15));

        assertThat(day.start()).isEqualTo(Instant.parse("2026-11-14T17:00:00Z"));
        assertThat(day.endExclusive()).isEqualTo(Instant.parse("2026-11-15T17:00:00Z"));
    }

    @Test
    void malformedKeyIsRejectedNamingGroup() {
        for (String key : new String[] {
            "workflow:not-a-uuid", "workflow:", "day:2026-13-01", "day:2026-1-1", "other", "", "Upload", "upload ", "day:"}) {
            assertThatThrownBy(() -> AssetGroupKeys.parse(key))
                    .as(key)
                    .isInstanceOfSatisfying(AssetRequestRejected.class,
                            rejected -> assertThat(rejected.parameter()).isEqualTo("group"));
        }
        assertThatThrownBy(() -> AssetGroupKeys.parse(null)).isInstanceOf(AssetRequestRejected.class);
    }

    @Test
    void keyExpressionIsOneSqlRuleForEachGrouping() {
        assertThat(AssetGroupKeys.keyExpression(GroupBy.WORKFLOW, "a"))
                .isEqualTo("CASE WHEN a.source = 'run' AND a.workflow_id IS NOT NULL"
                        + " THEN 'workflow:' || a.workflow_id::text ELSE 'upload' END");
        assertThat(AssetGroupKeys.keyExpression(GroupBy.DAY, "sa"))
                .isEqualTo("'day:' || to_char(sa.created_at AT TIME ZONE 'Asia/Ho_Chi_Minh', 'YYYY-MM-DD')");
    }

    @Test
    void keyExpressionRefusesAnAliasThatIsNotAnIdentifier() {
        assertThatThrownBy(() -> AssetGroupKeys.keyExpression(GroupBy.DAY, "a; DROP TABLE x"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
