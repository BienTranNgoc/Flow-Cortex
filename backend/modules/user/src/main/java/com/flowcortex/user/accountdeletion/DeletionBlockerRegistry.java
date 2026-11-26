package com.flowcortex.user.accountdeletion;

import com.flowcortex.user.api.AccountDeletionBlocker;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/** Every registered {@link AccountDeletionBlocker}; startup fails on a duplicate or malformed key. */
@Component
class DeletionBlockerRegistry {

    private static final Pattern KEY = Pattern.compile("^[a-z][a-z0-9_]*\\.[a-z][a-z0-9_]*$");

    private final Map<String, AccountDeletionBlocker> byKey = new LinkedHashMap<>();

    DeletionBlockerRegistry(List<AccountDeletionBlocker> blockers) {
        blockers.stream().sorted(Comparator.comparing(AccountDeletionBlocker::key)).forEach(blocker -> {
            String key = blocker.key();
            if (key == null || !KEY.matcher(key).matches()) {
                throw new IllegalStateException(
                        "Account deletion blocker key '" + key + "' must look like <module>.<snake_name>");
            }
            if (byKey.putIfAbsent(key, blocker) != null) {
                throw new IllegalStateException("Account deletion blocker key '" + key + "' is registered twice");
            }
        });
    }

    Optional<AccountDeletionBlocker> find(String key) {
        return Optional.ofNullable(byKey.get(key));
    }

    /** All blockers in key order. */
    List<AccountDeletionBlocker> all() {
        return List.copyOf(byKey.values());
    }
}
