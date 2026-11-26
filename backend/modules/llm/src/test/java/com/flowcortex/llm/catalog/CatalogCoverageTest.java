package com.flowcortex.llm.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.llm.catalog.CatalogCoverage.Coverage;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CatalogCoverageTest {

    @Test
    void aRowTheEngineDeclaredIsRegistered() {
        Coverage coverage = CatalogCoverage.assemble(List.of("echo", "translate"), Set.of("echo", "translate"));

        assertThat(coverage.isRegistered("echo")).isTrue();
        assertThat(coverage.isRegistered("translate")).isTrue();
        assertThat(coverage.uncatalogued()).isEmpty();
    }

    @Test
    void aRowNobodyDeclaredIsNotRegistered() {
        Coverage coverage = CatalogCoverage.assemble(List.of("echo", "legacy.step"), Set.of("echo"));

        assertThat(coverage.isRegistered("echo")).isTrue();
        assertThat(coverage.isRegistered("legacy.step")).isFalse();
        assertThat(coverage.uncatalogued()).isEmpty();
    }

    @Test
    void aDeclaredKeyWithNoRowIsUncataloguedAndTheListIsSorted() {
        Coverage coverage = CatalogCoverage.assemble(List.of("echo"), Set.of("echo", "video.fetch", "connector.call"));

        assertThat(coverage.uncatalogued()).containsExactly("connector.call", "video.fetch");
    }

    @Test
    void nothingDeclaredYetMarksEveryRowUnregisteredAndListsNothing() {
        Coverage coverage = CatalogCoverage.assemble(List.of("echo", "translate"), Set.of());

        assertThat(coverage.isRegistered("echo")).isFalse();
        assertThat(coverage.isRegistered("translate")).isFalse();
        assertThat(coverage.uncatalogued()).isEmpty();
    }

    @Test
    void declaredCapabilitiesAreEmptyUntilTheEngineDeclaresThem() {
        DeclaredCapabilities declared = new DeclaredCapabilities();
        assertThat(declared.declared()).isEmpty();

        declared.declare(List.of("echo", "echo", "translate"));

        assertThat(declared.declared()).containsExactlyInAnyOrder("echo", "translate");
    }
}
