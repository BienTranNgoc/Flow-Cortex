package com.flowcortex.billing.momo;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class MomoStartupCheckTest {

    private static final String SECRET = "very-secret-value-0001";
    private static final String CALLBACK = "https://app.example.com" + MomoCallbackRoute.PATH;

    private static MomoProperties complete() {
        return props("MOMOTEST", "access-key-value", SECRET, "https://app.example.com/billing/return", CALLBACK,
                List.of("wallet", "domestic_card"), "118.69.210.244/32,118.68.171.0/24");
    }

    private static MomoProperties props(String partner, String access, String secret, String redirect, String ipn,
            List<String> instruments, String allowlist) {
        return new MomoProperties(true, null, partner, access, secret, redirect, ipn, instruments, allowlist, null);
    }

    @Test
    void aCompleteConfigurationHasNoProblems() {
        assertThat(MomoStartupCheck.problems(complete(), true)).isEmpty();
    }

    @Test
    void eachMissingRequiredValueNamesItsPropertyAndNoValue() {
        MomoProperties noSecret = props("MOMOTEST", "access-key-value", "", "https://r", CALLBACK,
                List.of("wallet"), "1.2.3.4");
        MomoProperties noPartner = props(null, "access-key-value", SECRET, "https://r", CALLBACK,
                List.of("wallet"), "1.2.3.4");
        MomoProperties noAccess = props("MOMOTEST", " ", SECRET, "https://r", CALLBACK, List.of("wallet"), "1.2.3.4");
        MomoProperties noRedirect = props("MOMOTEST", "access-key-value", SECRET, null, CALLBACK,
                List.of("wallet"), "1.2.3.4");
        MomoProperties noCallback = props("MOMOTEST", "access-key-value", SECRET, "https://r", "",
                List.of("wallet"), "1.2.3.4");

        assertThat(MomoStartupCheck.problems(noSecret, true)).singleElement().asString()
                .contains("flowcortex.billing.momo.secret-key");
        assertThat(MomoStartupCheck.problems(noPartner, true)).singleElement().asString()
                .contains("flowcortex.billing.momo.partner-code");
        assertThat(MomoStartupCheck.problems(noAccess, true)).singleElement().asString()
                .contains("flowcortex.billing.momo.access-key");
        assertThat(MomoStartupCheck.problems(noRedirect, true)).singleElement().asString()
                .contains("flowcortex.billing.momo.redirect-url");
        assertThat(MomoStartupCheck.problems(noCallback, true)).singleElement().asString()
                .contains("flowcortex.billing.momo.ipn-url");
    }

    @Test
    void noProblemTextCarriesACredentialValue() {
        MomoProperties bad = props("MOMOTEST", "access-key-value", SECRET, "https://r", "https://x/wrong",
                List.of("nope"), "bad entry");

        assertThat(String.join("\n", MomoStartupCheck.problems(bad, false)))
                .doesNotContain(SECRET).doesNotContain("access-key-value").doesNotContain("MOMOTEST");
    }

    @Test
    void aCallbackUrlOnTheWrongPathNamesBothPaths() {
        MomoProperties wrong = props("MOMOTEST", "access-key-value", SECRET, "https://r",
                "https://app.example.com/api/v1/payments/momo", List.of("wallet"), "1.2.3.4");

        assertThat(MomoStartupCheck.problems(wrong, true)).singleElement().asString()
                .contains("/api/v1/payments/momo").contains("/api/v1/billing/webhooks/momo");
    }

    @Test
    void emptyOrUnknownInstrumentsAreAProblem() {
        assertThat(MomoStartupCheck.problems(
                props("P", "A", SECRET, "https://r", CALLBACK, List.of(), "1.2.3.4"), true))
                .singleElement().asString().contains("offered-instruments");
        assertThat(MomoStartupCheck.problems(
                props("P", "A", SECRET, "https://r", CALLBACK, List.of("wallet", "crypto"), "1.2.3.4"), true))
                .singleElement().asString().contains("offered-instruments").contains("unknown");
    }

    @Test
    void anEmptyAllowlistIsAProblem() {
        assertThat(MomoStartupCheck.problems(
                props("P", "A", SECRET, "https://r", CALLBACK, List.of("wallet"), ""), true))
                .singleElement().asString().contains("callback-source-allowlist");
    }

    @Test
    void anUnreadableAllowlistEntryIsNamed() {
        assertThat(MomoStartupCheck.problems(
                props("P", "A", SECRET, "https://r", CALLBACK, List.of("wallet"),
                        "118.69.210.244 118.68.171.198"), true))
                .singleElement().asString().contains("callback-source-allowlist")
                .contains("118.69.210.244 118.68.171.198");
    }

    @Test
    void aMissingOrderLookupIsAProblem() {
        assertThat(MomoStartupCheck.problems(complete(), false)).singleElement().asString()
                .contains("cross-checked");
    }

    @Test
    void twoFaultsGiveTwoProblems() {
        MomoProperties two = props("", "A", "", "https://r", CALLBACK, List.of("wallet"), "1.2.3.4");

        assertThat(MomoStartupCheck.problems(two, true)).hasSize(2);
    }

    @Test
    void anUnusableCircuitSettingIsAProblem() {
        MomoProperties zero = new MomoProperties(true, null, "P", "A", SECRET, "https://r", CALLBACK,
                List.of("wallet"), "1.2.3.4", new MomoProperties.Circuit(true, 0, java.time.Duration.ZERO));

        assertThat(MomoStartupCheck.problems(zero, true)).hasSize(2);
    }

    @Test
    void propertiesNeverPrintCredentials() {
        assertThat(complete().toString()).doesNotContain(SECRET).doesNotContain("access-key-value");
    }
}
