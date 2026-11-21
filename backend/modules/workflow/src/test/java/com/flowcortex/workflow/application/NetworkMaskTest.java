package com.flowcortex.workflow.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NetworkMaskTest {

    @Test
    void masksIpv4ToTheFirstTwoOctets() {
        assertThat(NetworkMask.of("198.51.100.23")).isEqualTo("198.51.x.x");
    }

    @Test
    void treatsIpv4MappedIpv6AsIpv4() {
        assertThat(NetworkMask.of("::ffff:198.51.100.23")).isEqualTo("198.51.x.x");
    }

    @Test
    void masksTheLastTwoGroupsOfIpv6() {
        assertThat(NetworkMask.of("2001:db8:1:2:3:4:5:6")).isEqualTo("2001:db8:1:2:3:4:x:x");
        assertThat(NetworkMask.of("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:x:x");
    }

    @Test
    void keepsAMissingAddressNull() {
        assertThat(NetworkMask.of(null)).isNull();
        assertThat(NetworkMask.of("  ")).isNull();
    }

    @Test
    void anUnparseableAddressIsNotStored() {
        assertThat(NetworkMask.of("not-an-address")).isNull();
        assertThat(NetworkMask.of("300.1.2.3")).isNull();
    }
}
