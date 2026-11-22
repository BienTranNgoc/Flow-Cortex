import type { RoleResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { can, type Caller } from '../../app/permissions';

export const END_USER_ROLES = ['MEMBER', 'CREATOR'] as const;
export const STAFF_ROLES = [
  'OPERATION_MANAGER',
  'CUSTOMER_SUPPORT',
  'CONTENT_EDITOR',
  'PLATFORM_OPERATOR',
] as const;
/** Every system role code, in the order the roles list shows them. */
export const ROLE_CODES = ['ADMIN', ...STAFF_ROLES, ...END_USER_ROLES] as const;

/** The longest role code the server accepts. */
export const ROLE_CODE_MAX_LENGTH = 20;

const ROLES: readonly string[] = ROLE_CODES;
const STATUSES: readonly string[] = ['ACTIVE', 'SUSPENDED'];

export type RoleKind = 'endUser' | 'superAdmin' | 'staff';

/**
 * Whether a role code is an end-user role, the super admin or staff. Every code outside the two
 * end-user codes and `ADMIN` is staff: the server refuses a custom end-user or super-admin role.
 */
export function roleKind(code: string): RoleKind {
  if (code === 'ADMIN') {
    return 'superAdmin';
  }
  return END_USER_ROLES.some((endUser) => endUser === code) ? 'endUser' : 'staff';
}

/** Names of the custom roles in `roles`, by code; a system role reads by its localized label instead. */
export function customRoleNames(
  roles: readonly Pick<RoleResponse, 'code' | 'name' | 'system'>[] | undefined,
): Readonly<Record<string, string>> {
  return Object.fromEntries(
    (roles ?? []).filter((role) => !role.system).map((role) => [role.code, role.name]),
  );
}

/**
 * The roles the caller may assign on the Users page: end-user roles with `user.account.change_role`,
 * and `ADMIN`, the staff roles and the custom staff roles in `roles` only when the caller also holds
 * `system.admin_account.manage`.
 */
export function assignableRoles(
  me: Caller | undefined,
  roles?: readonly Pick<RoleResponse, 'code' | 'system'>[],
): readonly string[] {
  if (!can(me, 'user.account.change_role')) {
    return [];
  }
  if (!can(me, 'system.admin_account.manage')) {
    return END_USER_ROLES;
  }
  const custom = (roles ?? [])
    .filter((role) => !role.system && roleKind(role.code) === 'staff')
    .map((role) => role.code);
  return [...ROLE_CODES, ...custom];
}

/**
 * The role's label in the active language: a system role by its localized label, any other role by the
 * name its admin gave it, falling back to the code when the name is not at hand.
 */
export function roleLabel(
  t: TFunction,
  role: string | undefined,
  roleName?: string | undefined,
): string {
  if (!role) {
    return '';
  }
  return ROLES.includes(role) ? t(`users.role.${role}`) : roleName || role;
}

/** The account status's label in the active language; no status reads as active, an unknown code is shown as sent. */
export function statusLabel(t: TFunction, status: string | undefined): string {
  const code = status || 'ACTIVE';
  return STATUSES.includes(code) ? t(`users.status.${code}`) : code;
}
