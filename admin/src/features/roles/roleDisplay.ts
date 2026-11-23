import type { RoleResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { roleLabel } from '../users/enumLabels';

/** A system role reads by its localized label, a custom role by the name its admin gave it. */
export function roleDisplayName(
  t: TFunction,
  role: Pick<RoleResponse, 'code' | 'name' | 'system'>,
) {
  return role.system ? roleLabel(t, role.code) : role.name;
}

/** The roles the Roles pages list: staff and the super admin, never an end-user role. */
export function isAdminRole(role: Pick<RoleResponse, 'staff' | 'superAdmin'>): boolean {
  return role.staff || role.superAdmin;
}

/** Admin accounts filtered to the holders of one role. */
export function accountsOfRoleHref(code: string): string {
  return `/admin-accounts?role=${encodeURIComponent(code)}`;
}
