package com.flowcortex.user.roles;

import com.flowcortex.shared.web.ConflictException;
import com.flowcortex.shared.web.LocalizedMessage;
import com.flowcortex.shared.web.UnprocessableEntityException;
import com.flowcortex.user.infrastructure.RoleDetailRow;
import com.flowcortex.user.infrastructure.RoleRepository;
import com.flowcortex.user.roles.RbacMutationSwitch.RbacOperation;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Replaces the key set of a staff role under an optimistic version. */
@Service
public class RolePermissionService {

    private static final String NOT_EDITABLE = "ROLE_NOT_EDITABLE";

    private final RoleRepository roles;
    private final RbacMutationSwitch mutationSwitch;

    RolePermissionService(RoleRepository roles, RbacMutationSwitch mutationSwitch) {
        this.roles = roles;
        this.mutationSwitch = mutationSwitch;
    }

    /**
     * Replaces the keys of {@code code} when {@code expectedVersion} is still its version. The conditional update
     * serializes two editors on the role row: the second waits, then matches nothing and answers 409.
     */
    @Transactional
    public void replace(UUID actor, String code, Collection<String> keys, int expectedVersion) {
        mutationSwitch.requireEnabled(RbacOperation.ROLE_PERMISSIONS_REPLACE, actor);
        RoleDetailRow role = roles.findDetail(code).orElseThrow(() -> RoleService.notFound(code));
        if (role.superAdmin() || !role.staff()) {
            throw new UnprocessableEntityException(new LocalizedMessage("role.notEditable", code), NOT_EDITABLE);
        }
        List<String> keySet = keys.stream().distinct().toList();
        PermissionSetPolicy.check(keySet, PermissionSetPolicy.permissionCatalog()).ifPresent(violation -> {
            throw violation.toException();
        });
        if (roles.bumpVersion(code, expectedVersion).isEmpty()) {
            throw new ConflictException(new LocalizedMessage("role.staleVersion", code));
        }
        roles.replaceKeys(code, keySet);
    }
}
