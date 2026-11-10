package com.flowcortex.billing.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.SettingSource;
import com.flowcortex.settings.api.SettingType;
import com.flowcortex.settings.api.Settings;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PaymentSettingCatalogTest {

    private final Settings settings = mock(Settings.class);

    private static PaymentSettingMeta meta(String key) {
        return new PaymentSettingMeta(key, PaymentSettingGroup.REGION, false);
    }

    private static SettingDescription described(String key) {
        return new SettingDescription(key, SettingType.INTEGER, List.of(), 1, 100, 5, 5, SettingSource.DEFAULT, null,
                null, null);
    }

    private PaymentSettingCatalog catalog(Set<String> declared, PaymentSettingMeta... metas) {
        when(settings.declaredKeys(SettingScope.PAYMENT_OPS)).thenReturn(declared);
        return new PaymentSettingCatalog(List.of(metas), settings);
    }

    @Test
    void metadataWithoutAKeyIsRefused() {
        assertThatThrownBy(() -> catalog(Set.of(), meta("payment.checkout.guard")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.checkout.guard");
    }

    @Test
    void aPaymentKeyWithoutMetadataIsRefused() {
        assertThatThrownBy(() -> catalog(Set.of("payment.region_gate.mode", "payment.checkout.guard"),
                meta("payment.region_gate.mode")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.checkout.guard");
    }

    @Test
    void twoMetadataForOneKeyAreRefused() {
        assertThatThrownBy(() -> catalog(Set.of("payment.region_gate.mode"), meta("payment.region_gate.mode"),
                meta("payment.region_gate.mode")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("payment.region_gate.mode");
    }

    @Test
    void generalKeysNeedNoMetadata() {
        PaymentSettingCatalog catalog = catalog(Set.of());

        assertThat(catalog.meta("support.assistant.enabled")).isEmpty();
        verify(settings).declaredKeys(SettingScope.PAYMENT_OPS);
        verifyNoMoreInteractions(settings);
    }

    @Test
    void servedKeysAreOrderedByGroupThenKey() {
        PaymentSettingCatalog catalog = catalog(Set.of("payment.b", "payment.a", "payment.c"), meta("payment.b"),
                meta("payment.a"), meta("payment.c"));

        assertThat(catalog.ordered(List.of(described("payment.c"), described("payment.a"), described("payment.b"))))
                .extracting(SettingDescription::name)
                .containsExactly("payment.a", "payment.b", "payment.c");
        assertThat(catalog.meta("payment.a")).contains(meta("payment.a"));
        assertThat(catalog.meta("payment.zzz")).isEqualTo(Optional.empty());
    }
}
