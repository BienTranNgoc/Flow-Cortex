package com.flowcortex.llm.registry;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Every {@link RegisteredProvider} in the application, found by key. */
@Component
public class ProviderRegistry {

    private final Map<String, RegisteredProvider> byKey = new LinkedHashMap<>();

    ProviderRegistry(List<RegisteredProvider> providers) {
        for (RegisteredProvider provider : providers) {
            if (byKey.putIfAbsent(provider.key(), provider) != null) {
                throw new IllegalStateException("Two providers are registered under the key '" + provider.key() + "'");
            }
        }
    }

    public Optional<RegisteredProvider> find(String key) {
        return Optional.ofNullable(key == null ? null : byKey.get(key));
    }

    public List<RegisteredProvider> list() {
        return List.copyOf(byKey.values());
    }
}
