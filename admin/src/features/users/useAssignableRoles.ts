import { can } from '../../app/permissions';
import { useMeQuery } from '../auth/useAuth';
import { useRolesQuery } from '../roles/useRoles';
import { assignableRoles, customRoleNames } from './enumLabels';

/** The role codes the caller may assign and the names of the custom ones; the role list is read only for a caller who may manage roles. */
export function useAssignableRoles() {
  const me = useMeQuery().data;
  const canListRoles = can(me, 'system.admin_account.manage') && can(me, 'system.role.view');
  const rolesQuery = useRolesQuery(canListRoles);
  return {
    assignable: assignableRoles(me, rolesQuery.data),
    names: customRoleNames(rolesQuery.data),
  };
}
