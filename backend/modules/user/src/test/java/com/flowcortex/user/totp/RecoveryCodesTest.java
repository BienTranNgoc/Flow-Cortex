package com.flowcortex.user.totp;

import static org.assertj.core.api.Assertions.assertThat;

import java.security.SecureRandom;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

class RecoveryCodesTest {

    @Test
    void tenDistinctCodesShapedInTwoGroupsOfFive() {
        List<String> codes = RecoveryCodes.generate(new SecureRandom());

        assertThat(codes).hasSize(10).allMatch(code -> code.matches("[A-Z2-7]{5}-[A-Z2-7]{5}"));
        assertThat(new HashSet<>(codes)).hasSize(10);
    }

    @Test
    void normalizeStripsSpacesHyphensAndUpperCases() {
        assertThat(RecoveryCodes.normalize("abcde 23456")).isEqualTo("ABCDE23456");
        assertThat(RecoveryCodes.normalize(" abcde-23456 ")).isEqualTo("ABCDE23456");
        assertThat(RecoveryCodes.normalize("ABCDE23456")).isEqualTo("ABCDE23456");
    }

    @Test
    void aRecoveryShapeIsRecognized() {
        assertThat(RecoveryCodes.looksLikeRecoveryCode("abcde 23456")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDE-23456")).isTrue();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("123456")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDE-2345")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode("ABCDE-2345!")).isFalse();
        assertThat(RecoveryCodes.looksLikeRecoveryCode(null)).isFalse();
    }
}
