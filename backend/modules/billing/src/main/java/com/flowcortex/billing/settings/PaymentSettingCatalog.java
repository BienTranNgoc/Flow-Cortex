package com.flowcortex.billing.settings;

import com.flowcortex.settings.api.SettingDescription;
import com.flowcortex.settings.api.SettingScope;
import com.flowcortex.settings.api.Settings;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Component;

/**
 * The payment metadata beans, checked once at startup against the keys the registry declares in scope
 * {@code PAYMENT_OPS}: each key has exactly one metadata bean and each bean names a declared key. The check
 * reads declarations only, never a stored value.
 */
@Component
class PaymentSettingCatalog {

    private static final Comparator<PaymentSettingMeta> BY_GROUP_THEN_KEY =
            Comparator.comparing(PaymentSettingMeta::group).thenComparing(PaymentSettingMeta::key);

    private final Map<String, PaymentSettingMeta> byKey;

    PaymentSettingCatalog(List<PaymentSettingMeta> metas, Settings settings) {
        Map<String, PaymentSettingMeta> indexed = new LinkedHashMap<>();
        for (PaymentSettingMeta meta : metas) {
            if (indexed.putIfAbsent(meta.key(), meta) != null) {
                throw new IllegalStateException("Payment metadata is declared twice for " + meta.key());
            }
        }
        Set<String> declared = settings.declaredKeys(SettingScope.PAYMENT_OPS);
        Set<String> withoutKey = new TreeSet<>(indexed.keySet());
        withoutKey.removeAll(declared);
        if (!withoutKey.isEmpty()) {
            throw new IllegalStateException(
                    "Payment metadata names keys no payment-ops setting declares: " + String.join(", ", withoutKey));
        }
        Set<String> withoutMeta = new TreeSet<>(declared);
        withoutMeta.removeAll(indexed.keySet());
        if (!withoutMeta.isEmpty()) {
            throw new IllegalStateException(
                    "Payment-ops settings without payment metadata: " + String.join(", ", withoutMeta));
        }
        this.byKey = Map.copyOf(indexed);
    }

    /** The metadata of a served key; empty for any other name. */
    Optional<PaymentSettingMeta> meta(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** The descriptions of served keys, by group declaration order then key. */
    List<SettingDescription> ordered(List<SettingDescription> descriptions) {
        return descriptions.stream()
                .sorted(Comparator.comparing(description -> byKey.get(description.name()), BY_GROUP_THEN_KEY))
                .toList();
    }
}
