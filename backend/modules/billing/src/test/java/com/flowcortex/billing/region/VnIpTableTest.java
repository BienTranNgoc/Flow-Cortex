package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

class VnIpTableTest {

    private static final Instant AT = Instant.parse("2026-11-01T00:00:00Z");

    private static VnIpTable table(List<VnIpTable.V4Record> v4, List<VnIpTable.V6Record> v6) {
        return VnIpTable.build(v4, v6, AT, List.of());
    }

    private static VnIpTable.V4Record v4(String start, long count) {
        return new VnIpTable.V4Record(IpAddress.parseIpv4(start), count);
    }

    private static VnIpTable.V6Record v6(String start, int prefix) {
        return new VnIpTable.V6Record(IpAddress.parseIpv6(start), prefix);
    }

    private static final VnIpTable SAMPLE = table(
            List.of(v4("14.160.0.0", 1L << 21), v4("113.160.0.0", 3)),
            List.of(v6("2402:800::", 32)));

    @Test
    void ipv4RangeEdgesAreInclusive() {
        assertThat(VnIpTable.verdict("14.160.0.0", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("14.191.255.255", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("14.192.0.0", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
        assertThat(VnIpTable.verdict("14.159.255.255", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
    }

    @Test
    void aRangeThatIsNotACidrBlockKeepsItsExactEdges() {
        assertThat(VnIpTable.verdict("113.160.0.2", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("113.160.0.3", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
    }

    @Test
    void touchingRangesAnswerAsOne() {
        VnIpTable merged = table(List.of(v4("20.0.0.0", 256), v4("20.0.1.0", 256)), List.of());

        assertThat(VnIpTable.verdict("20.0.0.255", merged)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("20.0.1.0", merged)).isEqualTo(RegionVerdict.VN);
        assertThat(merged.serialise()).contains("\"20.0.0.0\",\"20.0.1.255\"").doesNotContain("20.0.0.255");
    }

    @Test
    void anIpv6AddressInsideAndJustOutsideAPrefix() {
        assertThat(VnIpTable.verdict("2402:800::1", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("2402:800:ffff:ffff:ffff:ffff:ffff:ffff", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("2402:801::", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
    }

    @Test
    void anIpv4MappedIpv6AddressIsPlacedAsTheIpv4AddressItCarries() {
        assertThat(VnIpTable.verdict("::ffff:14.160.0.1", SAMPLE)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("::ffff:8.8.8.8", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
        assertThat(VnIpTable.verdict("::ffff:a0a0:0001", SAMPLE)).isEqualTo(RegionVerdict.FOREIGN);
        assertThat(VnIpTable.verdict("::ffff:0a00:0001", SAMPLE)).isEqualTo(RegionVerdict.UNKNOWN);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   ", "example.com", "999.1.1.1", "01.2.3.4", "1.2.3", "1.2.3.4.5", "10.1.2.3",
            "127.0.0.1", "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "::1", "fe80::1", "fe80::1%eth0",
            "2001:db8::1", "1:2:3:4:5:6:7:8:9", "12345::1", "::g", ":::", "1::2::3"})
    void aValueThatIsNotAPublicAddressIsUnknown(String value) {
        assertThat(VnIpTable.verdict(value, SAMPLE)).isEqualTo(RegionVerdict.UNKNOWN);
    }

    @Test
    void withoutATableEveryAddressIsUnknown() {
        assertThat(VnIpTable.verdict("14.160.0.1", null)).isEqualTo(RegionVerdict.UNKNOWN);
        assertThat(VnIpTable.verdict("8.8.8.8", null)).isEqualTo(RegionVerdict.UNKNOWN);
    }

    @Test
    void aSnapshotWrittenThenReadPlacesAddressesTheSame() {
        VnIpTable read = VnIpTable.deserialise(SAMPLE.serialise());

        assertThat(read.ipv4RecordCount()).isEqualTo(2);
        assertThat(read.ipv6RecordCount()).isEqualTo(1);
        assertThat(read.generatedAt()).isEqualTo(AT);
        assertThat(VnIpTable.verdict("14.191.255.255", read)).isEqualTo(RegionVerdict.VN);
        assertThat(VnIpTable.verdict("14.192.0.0", read)).isEqualTo(RegionVerdict.FOREIGN);
        assertThat(VnIpTable.verdict("2402:800::1", read)).isEqualTo(RegionVerdict.VN);
        assertThat(read.serialise()).isEqualTo(SAMPLE.serialise());
    }

    @Test
    void aSnapshotRangeWhoseStartIsAfterItsEndRefusesTheWholeSnapshot() {
        String bad = snapshotWith("[[\"14.160.0.0\",\"14.191.255.255\"],[\"30.0.0.9\",\"30.0.0.1\"]]", "[]");

        assertThatThrownBy(() -> VnIpTable.deserialise(bad)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aSnapshotWithAMalformedFieldIsRefused() {
        assertThatThrownBy(() -> VnIpTable.deserialise("not json")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnIpTable.deserialise("[]")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnIpTable.deserialise(snapshotWith("[[\"1.2.3.4\"]]", "[]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnIpTable.deserialise(snapshotWith("[]", "[[\"zz\",\"::1\"]]")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnIpTable.deserialise(snapshotWith("[]", "{}")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> VnIpTable.deserialise("{\"generatedAt\":\"yesterday\",\"sources\":[],"
                + "\"ipv4RecordCount\":0,\"ipv6RecordCount\":0,\"ipv4\":[],\"ipv6\":[]}"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void ipv6TextRoundTripsThroughTheParserAndFormatter() {
        BigInteger value = IpAddress.parseIpv6("2402:800:0:0:1:0:0:1");

        assertThat(IpAddress.formatIpv6(value)).isEqualTo("2402:800::1:0:0:1");
        assertThat(IpAddress.parseIpv6(IpAddress.formatIpv6(value))).isEqualTo(value);
        assertThat(IpAddress.formatIpv4(IpAddress.parseIpv4("14.160.0.1"))).isEqualTo("14.160.0.1");
    }

    private static String snapshotWith(String ipv4, String ipv6) {
        return "{\"generatedAt\":\"2026-11-01T00:00:00Z\",\"sources\":[],\"ipv4RecordCount\":1,"
                + "\"ipv6RecordCount\":0,\"ipv4\":" + ipv4 + ",\"ipv6\":" + ipv6 + "}";
    }
}
