package com.flowcortex.billing.provider;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** The providers present in this deployment, by key. Empty when none is enabled. */
@Component
public class PaymentProviders {

    private final Map<String, PaymentProvider> byKey = new HashMap<>();

    @Autowired
    public PaymentProviders(ObjectProvider<PaymentProvider> providers) {
        this(providers.orderedStream().toList());
    }

    public PaymentProviders(Collection<PaymentProvider> providers) {
        for (PaymentProvider provider : providers) {
            if (byKey.putIfAbsent(provider.key(), provider) != null) {
                throw new IllegalStateException("two payment providers share the key " + provider.key());
            }
        }
    }

    public Optional<PaymentProvider> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }
}
