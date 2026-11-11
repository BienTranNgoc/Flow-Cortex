package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** The staff lists the admin console may ask for, each keyed by the permission its holders are listed for. */
final class AssigneeLists {

    /** List key (the permission whose holders are listed) to the key a caller needs to read that list. */
    private static final Map<Permission, Permission> CALLER_KEY_BY_LIST =
            Map.of(Permission.SUPPORT_TICKET_REPLY, Permission.SUPPORT_TICKET_ASSIGN);

    private AssigneeLists() {
    }

    static Optional<Permission> listFor(String listKey) {
        return Permission.fromKey(listKey).filter(CALLER_KEY_BY_LIST::containsKey);
    }

    static Optional<Permission> callerKeyFor(String listKey) {
        return listFor(listKey).map(CALLER_KEY_BY_LIST::get);
    }

    static Set<Permission> callerKeys() {
        return Set.copyOf(CALLER_KEY_BY_LIST.values());
    }
}
