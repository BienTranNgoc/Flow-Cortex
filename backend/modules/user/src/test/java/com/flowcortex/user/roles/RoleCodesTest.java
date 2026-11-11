package com.flowcortex.user.roles;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class RoleCodesTest {

    private static final String CODE_FORMAT = "^[A-Z][A-Z0-9_]{0,19}$";

    @Test
    void aVietnameseNameLosesItsMarksAndDStroke() {
        assertThat(RoleCodes.slug("Điều phối nội dung")).isEqualTo("DIEU_PHOI_NOI_DUNG");
    }

    @Test
    void separatorsCollapseToOneUnderscore() {
        assertThat(RoleCodes.slug("Hỗ trợ cấp 2")).isEqualTo("HO_TRO_CAP_2");
    }

    @Test
    void aNameWithNoLetterOrDigitFallsBackToRole() {
        assertThat(RoleCodes.slug("!!!")).isEqualTo("ROLE");
    }

    @Test
    void aLeadingDigitGetsAnRPrefix() {
        assertThat(RoleCodes.slug("2nd line")).isEqualTo("R_2ND_LINE");
    }

    @Test
    void aLongNameIsCutToTwentyAndTheTrailingUnderscoreOfTheCutIsTrimmed() {
        assertThat(RoleCodes.slug("Quản lý vận hành kh vực")).isEqualTo("QUAN_LY_VAN_HANH_KH");
    }

    @Test
    void aSuffixReplacesTheTailOfALongBase() {
        assertThat(RoleCodes.withSuffix("QUAN_LY_VAN_HANH_KHU", 2)).isEqualTo("QUAN_LY_VAN_HANH_K_2");
    }

    @Test
    void aCutBaseNeverLeavesADoubleUnderscore() {
        assertThat(RoleCodes.withSuffix("ABCDEFGHIJKLMNOPQ_RS", 2)).isEqualTo("ABCDEFGHIJKLMNOPQ_2");
    }

    @Test
    void aTwoDigitSuffixKeepsTheShortBase() {
        assertThat(RoleCodes.withSuffix("ROLE", 10)).isEqualTo("ROLE_10");
    }

    @Test
    void newIsReservedForTheConsoleRoute() {
        assertThat(RoleCodes.isReserved("NEW")).isTrue();
        assertThat(RoleCodes.isReserved("NEW_2")).isFalse();
        assertThat(RoleCodes.slug("New")).isEqualTo("NEW");
        assertThat(RoleCodes.withSuffix(RoleCodes.slug("New"), 2)).isEqualTo("NEW_2");
    }

    @Test
    void everyOutputMatchesTheCodeFormat() {
        List<String> names = List.of("Điều phối nội dung", "Hỗ trợ cấp 2", "!!!", "2nd line", "x",
                "Quản lý vận hành kh vực", "  __ ", "ĐĐĐ", "a".repeat(300));
        for (String name : names) {
            String slug = RoleCodes.slug(name);
            assertThat(slug).matches(CODE_FORMAT);
            for (int n = 2; n <= 12; n++) {
                assertThat(RoleCodes.withSuffix(slug, n)).matches(CODE_FORMAT);
            }
        }
    }
}
