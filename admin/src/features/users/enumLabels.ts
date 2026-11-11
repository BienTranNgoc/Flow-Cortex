import type { TFunction } from 'i18next';
import { can, type Caller } from '../../app/permissions';
import type { StatusTone } from '../../app/StatusPill';

export const END_USER_ROLES = ['MEMBER', 'CREATOR'] as const;
export const STAFF_ROLES = [
  'OPERATION_MANAGER',
  'CUSTOMER_SUPPORT',
  'CONTENT_EDITOR',
  'PLATFORM_OPERATOR',
] as const;
/** Every role code, in the order the roles list shows them. */
export const ROLE_CODES = ['ADMIN', ...STAFF_ROLES, ...END_USER_ROLES] as const;

const ROLES: readonly string[] = ROLE_CODES;
const STATUSES: readonly string[] = ['ACTIVE', 'SUSPENDED'];

/**
 * The roles the caller may assign on the Users page: end-user roles with `user.account.change_role`,
 * and `ADMIN` plus the staff roles only when the caller also holds `system.admin_account.manage`.
 */
export function assignableRoles(me: Caller | undefined): readonly string[] {
  if (!can(me, 'user.account.change_role')) {
    return [];
  }
  return can(me, 'system.admin_account.manage') ? ROLE_CODES : END_USER_ROLES;
}

/** The role's label in the active language; a code this console does not know is shown as sent. */
export function roleLabel(t: TFunction, role: string | undefined): string {
  if (!role) {
    return '';
  }
  return ROLES.includes(role) ? t(`users.role.${role}`) : role;
}

const STATES: readonly string[] = ['active', 'pending_confirmation', 'locked', 'suspended'];
const SIGN_IN_METHODS: readonly string[] = ['PASSWORD', 'GOOGLE'];

const STATE_TONES: Record<string, StatusTone> = {
  active: 'success',
  pending_confirmation: 'info',
  locked: 'warning',
  suspended: 'error',
};

/** The account state's label in the active language; a state this console does not know is shown as sent. */
export function stateLabel(t: TFunction, state: string | undefined): string {
  if (!state) {
    return '';
  }
  return STATES.includes(state) ? t(`users.state.${state}`) : state;
}

/** The pill tone of an account state; an unknown state reads as muted. */
export function stateTone(state: string | undefined): StatusTone {
  return (state && STATE_TONES[state]) || 'muted';
}

/** A sign-in method's label in the active language; a method this console does not know is shown as sent. */
export function signInMethodLabel(t: TFunction, method: string): string {
  return SIGN_IN_METHODS.includes(method) ? t(`users.signInMethod.${method}`) : method;
}

/** The account status's label in the active language; no status reads as active, an unknown code is shown as sent. */
export function statusLabel(t: TFunction, status: string | undefined): string {
  const code = status || 'ACTIVE';
  return STATUSES.includes(code) ? t(`users.status.${code}`) : code;
}
