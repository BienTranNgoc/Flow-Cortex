package com.flowcortex.trash.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TrashKeysTest {

    @Test
    void typeKeyAcceptsLowercaseDigitsUnderscore2To32() {
        assertThat(TrashKeys.validType("demo")).isTrue();
        assertThat(TrashKeys.validType("demo_late")).isTrue();
        assertThat(TrashKeys.validType("d1")).isTrue();
        assertThat(TrashKeys.validType("a" + "b".repeat(31))).isTrue();

        assertThat(TrashKeys.validType("Demo")).isFalse();
        assertThat(TrashKeys.validType("d")).isFalse();
        assertThat(TrashKeys.validType("demo-x")).isFalse();
        assertThat(TrashKeys.validType("1demo")).isFalse();
        assertThat(TrashKeys.validType("a" + "b".repeat(32))).isFalse();
        assertThat(TrashKeys.validType("demo\n")).isFalse();
        assertThat(TrashKeys.validType("")).isFalse();
        assertThat(TrashKeys.validType(null)).isFalse();
    }

    @Test
    void itemIdAccepts1To64OfAlnumDashUnderscore() {
        assertThat(TrashKeys.validItemId("prj_a-1")).isTrue();
        assertThat(TrashKeys.validItemId("a")).isTrue();
        assertThat(TrashKeys.validItemId("a".repeat(64))).isTrue();

        assertThat(TrashKeys.validItemId("a b")).isFalse();
        assertThat(TrashKeys.validItemId("")).isFalse();
        assertThat(TrashKeys.validItemId("a".repeat(65))).isFalse();
        assertThat(TrashKeys.validItemId("a\n")).isFalse();
        assertThat(TrashKeys.validItemId(null)).isFalse();
    }

    @Test
    void locationCodeFormat() {
        assertThat(TrashKeys.validLocationCode("trash.location.demo")).isTrue();
        assertThat(TrashKeys.validLocationCode("a_b-c.d")).isTrue();
        assertThat(TrashKeys.validLocationCode("a".repeat(128))).isTrue();

        assertThat(TrashKeys.validLocationCode("")).isFalse();
        assertThat(TrashKeys.validLocationCode("a".repeat(129))).isFalse();
        assertThat(TrashKeys.validLocationCode("has space")).isFalse();
        assertThat(TrashKeys.validLocationCode("a/b")).isFalse();
        assertThat(TrashKeys.validLocationCode(null)).isFalse();
    }
}
