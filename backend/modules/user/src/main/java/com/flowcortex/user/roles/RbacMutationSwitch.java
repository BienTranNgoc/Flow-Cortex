package com.flowcortex.user.roles;

import com.flowcortex.settings.api.Settings;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.LocalizedStatusException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/**
 * Refuses role and permission edits with 503 while the super admin's kill switch reads off. The switch
 * is read from the store on every call, so a flip applies to the next request.
 */
@Component
public class RbacMutationSwitch {

    /** The operations the switch gates, named in the refusal log line. */
    public enum RbacOperation {
        ROLE_CREATE,
        ROLE_UPDATE,
        ROLE_DELETE,
        ROLE_PERMISSIONS_REPLACE,
        ACCOUNT_ROLE_CHANGE,
        USER_ROLE_CHANGE,
        STAFF_ACCOUNT_CREATE
    }

    private static final Logger LOG = LoggerFactory.getLogger(RbacMutationSwitch.class);

    private final Settings settings;

    RbacMutationSwitch(Settings settings) {
        this.settings = settings;
    }

    /** Throws 503 {@code rbac.mutationsDisabled} when the switch reads off; a degraded read counts as off. */
    public void requireEnabled(RbacOperation operation, UUID actor) {
        if (Boolean.TRUE.equals(settings.get(RbacRuntimeSettings.MUTATIONS_ENABLED).effective())) {
            return;
        }
        LOG.info("rbac.mutation outcome=refused_switch_off operation={} actor={}", operation, actor);
        throw new LocalizedStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                new LocalizedMessage("rbac.mutationsDisabled"));
    }
}
