package com.flowcortex.billing.region;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class RirDelegatedParserTest {

    private static final String FILE = String.join("\n",
            "# comment",
            "2|apnic|20261107|5|19830613|20261106|+1000",
            "apnic|*|ipv4|*|3|summary",
            "apnic|VN|ipv4|14.160.0.0|2097152|20100101|allocated",
            "apnic|VN|ipv4|113.160.0.0|768|20100101|assigned",
            "apnic|VN|ipv4|1.2.3.0|256|20100101|available",
            "apnic|VN|ipv4|1.2.4.0|256|20100101|reserved",
            "apnic|JP|ipv4|2.2.2.0|256|20100101|allocated",
            "apnic|VN|ipv6|2402:800::|32|20100101|allocated",
            "apnic|vn|ipv6|2405:4800::|32|20100101|assigned",
            "apnic|VN|asn|38731|1|20100101|allocated",
            "");

    @Test
    void onlyAllocatedAndAssignedVietnamRowsEnter() {
        RirDelegatedParser.Parsed parsed = RirDelegatedParser.parse(FILE);

        assertThat(parsed.ipv4()).containsExactly(
                new VnIpTable.V4Record(IpAddress.parseIpv4("14.160.0.0"), 2097152),
                new VnIpTable.V4Record(IpAddress.parseIpv4("113.160.0.0"), 768));
        assertThat(parsed.ipv6()).hasSize(2);
        assertThat(parsed.malformed()).isZero();
    }

    @Test
    void headerFieldsAreRead() {
        RirDelegatedParser.Parsed parsed = RirDelegatedParser.parse(FILE);

        assertThat(parsed.registry()).isEqualTo("apnic");
        assertThat(parsed.serial()).isEqualTo("20261107");
        assertThat(parsed.endDate()).isEqualTo("20261106");
    }

    @Test
    void noHeaderLeavesRegistryNull() {
        assertThat(RirDelegatedParser.parse("apnic|VN|ipv4|14.160.0.0|256|20100101|allocated\n").registry()).isNull();
    }

    @Test
    void unreadableVietnamRowsAreCountedNotAdded() {
        String file = String.join("\n",
                "2|apnic|1|1|a|b|+0",
                "apnic|VN|ipv4|not-an-ip|256|20100101|allocated",
                "apnic|VN|ipv4|14.0.0.0|abc|20100101|allocated",
                "apnic|VN|ipv4|14.0.0.0|0|20100101|allocated",
                "apnic|VN|ipv4|255.255.255.0|512|20100101|allocated",
                "apnic|VN|ipv4|14.0.0.0|256",
                "apnic|VN|ipv6|zzzz::|32|20100101|allocated",
                "apnic|VN|ipv6|2402:800::|129|20100101|allocated",
                "apnic|JP|ipv4|garbage|1|20100101|allocated",
                "");

        RirDelegatedParser.Parsed parsed = RirDelegatedParser.parse(file);

        assertThat(parsed.malformed()).isEqualTo(7);
        assertThat(parsed.ipv4()).isEmpty();
        assertThat(parsed.ipv6()).isEmpty();
    }

    @Test
    void md5FileWordingsBothMatch() {
        byte[] content = "hello".getBytes(StandardCharsets.UTF_8);
        String hex = RirDelegatedParser.md5Hex(content);

        assertThat(RirDelegatedParser.md5Matches(content, "MD5 (delegated-x) = " + hex + "\n")).isTrue();
        assertThat(RirDelegatedParser.md5Matches(content, hex.toUpperCase() + "  delegated-x\n")).isTrue();
        assertThat(RirDelegatedParser.md5Matches(content, "0".repeat(32))).isFalse();
        assertThat(RirDelegatedParser.md5Matches(content, "no digest here")).isFalse();
    }
}
