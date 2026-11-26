package com.flowcortex.credit.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RefundPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected", "probe_failed"})
    void aFailureCausedByTheMemberIsNotRefunded(String code) {
        assertThat(RefundPolicy.refunds(code)).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"lease_expired", "queue_timeout", "run_timeout", "contract_violation", "media_disabled",
            "worker_crashed", "approval_rejected", ""})
    void aSystemOrUnknownFailureIsRefunded(String code) {
        assertThat(RefundPolicy.refunds(code)).isTrue();
    }

    @Test
    void aFailureWithNoCodeIsRefunded() {
        assertThat(RefundPolicy.refunds(null)).isTrue();
    }

    @Test
    void theUserFaultListIsExactlyTheFourMemberCausedCodes() {
        assertThat(RefundPolicy.USER_FAULT)
                .containsExactlyInAnyOrder("INSUFFICIENT_CREDITS", "APPROVAL_REJECTED", "input_rejected", "probe_failed");
    }

    @Test
    void userFaultEqualsTheContractsUserSourceCodes() throws IOException {
        String dir = Objects.requireNonNull(System.getProperty("flowcortex.contracts.dir"),
                "system property flowcortex.contracts.dir is not set (see modules/credit/build.gradle.kts)");
        JsonNode codes = JsonMapper.builder().build()
                .readTree(Files.readString(Path.of(dir, "step-failure-taxonomy.json"))).path("codes");
        Set<String> contractUserCodes = new TreeSet<>();
        for (String code : codes.propertyNames()) {
            if ("user".equals(codes.path(code).path("source").asString())) {
                contractUserCodes.add(code);
            }
        }

        Set<String> onlyHere = new TreeSet<>(RefundPolicy.USER_FAULT);
        onlyHere.removeAll(contractUserCodes);
        Set<String> onlyInContract = new TreeSet<>(contractUserCodes);
        onlyInContract.removeAll(RefundPolicy.USER_FAULT);

        assertThat(onlyHere).as("USER_FAULT codes the contract does not mark source user").isEmpty();
        assertThat(onlyInContract).as("source user codes of the contract missing from USER_FAULT").isEmpty();
    }
}
