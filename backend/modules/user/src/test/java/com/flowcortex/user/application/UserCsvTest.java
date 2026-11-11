package com.flowcortex.user.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.flowcortex.user.api.UserView;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class UserCsvTest {

    private static final UUID ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final Instant CREATED = Instant.parse("2026-11-08T03:04:05Z");

    private static UserView view(String name, String username, Instant confirmed, List<String> methods) {
        return new UserView(ID, UUID.randomUUID(), "a@example.com", name, "MEMBER", "ACTIVE", CREATED, CREATED,
                username, "active", confirmed, null, methods, false, false);
    }

    @Test
    void cellQuotesAndDoublesInnerQuotes() {
        assertThat(UserCsv.cell("Lê Thị \"Bé\"")).isEqualTo("\"Lê Thị \"\"Bé\"\"\"");
    }

    @ParameterizedTest
    @ValueSource(strings = {"=", "+", "-", "@", "\t", "\r"})
    void cellPrefixesAnApostropheToFormulaTriggers(String trigger) {
        assertThat(UserCsv.cell(trigger + "x")).isEqualTo("\"'" + trigger + "x\"");
    }

    @Test
    void cellNeutralisesAFormulaWithQuotes() {
        assertThat(UserCsv.cell("=HYPERLINK(\"http://x\",\"y\")"))
                .isEqualTo("\"'=HYPERLINK(\"\"http://x\"\",\"\"y\"\")\"");
    }

    @Test
    void cellOfNullIsAnEmptyQuotedCell() {
        assertThat(UserCsv.cell(null)).isEqualTo("\"\"");
    }

    @Test
    void headerIsTheFixedEnglishColumnList() {
        assertThat(UserCsv.header()).isEqualTo(
                "ID,Name,Email,Username,Role,Status,State,Email confirmed,Sign-in methods,Created\r\n");
    }

    @Test
    void rowRendersInstantsAsIsoUtcAndJoinsSignInMethods() {
        String row = UserCsv.row(view("Lê Thị", "bethi", Instant.parse("2026-11-09T01:02:03Z"),
                List.of("PASSWORD", "GOOGLE")));

        assertThat(row).isEqualTo("\"" + ID + "\",\"Lê Thị\",\"a@example.com\",\"bethi\",\"MEMBER\",\"ACTIVE\","
                + "\"active\",\"2026-11-09T01:02:03Z\",\"PASSWORD+GOOGLE\",\"2026-11-08T03:04:05Z\"\r\n");
    }

    @Test
    void rowLeavesMissingValuesEmpty() {
        String row = UserCsv.row(view("Bé", null, null, List.of()));

        assertThat(row).contains("\"Bé\",\"a@example.com\",\"\",\"MEMBER\"")
                .contains("\"active\",\"\",\"\",\"2026-11-08T03:04:05Z\"\r\n");
    }

    @Test
    void rowNeutralisesAFormulaName() {
        assertThat(UserCsv.row(view("=1+1", null, null, List.of()))).contains(",\"'=1+1\",");
    }

    @Test
    void bomIsTheUtf8ByteOrderMark() {
        assertThat(UserCsv.BOM).isEqualTo("﻿");
    }

    @Test
    void fileNameIsDatedInUtc() {
        assertThat(UserCsv.fileName(CREATED)).isEqualTo("users-20261108-0304.csv");
    }
}
