package com.flowcortex.billing.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class CallbackSourceAllowlistTest {

    private static CallbackSourceAllowlist of(String text) {
        CallbackSourceAllowlist.Result result = CallbackSourceAllowlist.parse(text);
        assertThat(result.unreadable()).isEmpty();
        return new CallbackSourceAllowlist(result.entries());
    }

    @Test
    void aSingleAddressAdmitsOnlyItself() {
        CallbackSourceAllowlist list = of("118.69.210.244/32");

        assertThat(list.admits("118.69.210.244")).isTrue();
        assertThat(list.admits("118.69.210.245")).isFalse();
    }

    @Test
    void aRangeAdmitsItsMembers() {
        CallbackSourceAllowlist list = of("118.68.171.0/24");

        assertThat(list.admits("118.68.171.198")).isTrue();
        assertThat(list.admits("118.68.172.1")).isFalse();
    }

    @Test
    void anIpv6RangeAdmitsInsideAndRefusesOutside() {
        CallbackSourceAllowlist list = of("2001:db8:1:2::/64");

        assertThat(list.admits("2001:db8:1:2:aaaa::1")).isTrue();
        assertThat(list.admits("2001:db8:1:3::1")).isFalse();
    }

    @Test
    void anIpv4MappedCallerMatchesTheIpv4Entry() {
        assertThat(of("118.69.210.244/32").admits("::ffff:118.69.210.244")).isTrue();
    }

    @Test
    void aBareAddressIsAHostEntry() {
        assertThat(of("118.69.210.244").admits("118.69.210.245")).isFalse();
        assertThat(of("118.69.210.244").admits("118.69.210.244")).isTrue();
        assertThat(of("2001:db8::7").admits("2001:db8::7")).isTrue();
        assertThat(of("2001:db8::7").admits("2001:db8::8")).isFalse();
    }

    @Test
    void entriesAreSeparatedByCommasAndABlankPieceIsIgnored() {
        CallbackSourceAllowlist list = of(" 118.69.210.244 , ,118.68.171.198/32,");

        assertThat(list.entries()).hasSize(2);
        assertThat(list.admits("118.68.171.198")).isTrue();
    }

    @Test
    void aSpaceInsteadOfACommaMakesTheEntryUnreadable() {
        CallbackSourceAllowlist.Result result = CallbackSourceAllowlist.parse("118.69.210.244 118.68.171.198");

        assertThat(result.unreadable()).containsExactly("118.69.210.244 118.68.171.198");
        assertThat(result.entries()).isEmpty();
    }

    @Test
    void badPrefixesAndNamesAreUnreadable() {
        assertThat(CallbackSourceAllowlist.parse("1.2.3.4/33,1.2.3.4/x,example.com,2001:db8::/129,1.2.3.4/-1")
                .unreadable()).hasSize(5);
    }

    @Test
    void anEmptyListHasNoEntriesAndNoUnreadable() {
        CallbackSourceAllowlist.Result result = CallbackSourceAllowlist.parse("");

        assertThat(result.entries()).isEmpty();
        assertThat(result.unreadable()).isEmpty();
        assertThat(CallbackSourceAllowlist.parse(null).entries()).isEmpty();
    }

    @Test
    void nullBlankAndHostnameCallersAreRefused() {
        CallbackSourceAllowlist list = of("118.69.210.244");

        assertThat(list.admits(null)).isFalse();
        assertThat(list.admits("  ")).isFalse();
        assertThat(list.admits("momo.vn")).isFalse();
        assertThat(list.admits("118.69.210.244%eth0")).isFalse();
    }
}
