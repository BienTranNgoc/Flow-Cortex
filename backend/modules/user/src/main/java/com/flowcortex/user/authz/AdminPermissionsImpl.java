package com.flowcortex.user.authz;

import com.flowcortex.shared.security.authz.Permission;
import com.flowcortex.user.api.AdminPermissions;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
class AdminPermissionsImpl implements AdminPermissions {

    private final CallerFactsLoader factsLoader;

    AdminPermissionsImpl(CallerFactsLoader factsLoader) {
        this.factsLoader = factsLoader;
    }

    @Override
    public void require(UUID userId, Permission key) {
        AdminAuthorizationDecision.decide(new Posture.AllOf(Set.of(key)), factsLoader.load(userId)).requireAllowed();
    }
}
