import type { TFunction } from 'i18next';

const ROLES: readonly string[] = ['ADMIN', 'MEMBER', 'CREATOR'];
const STATUSES: readonly string[] = ['ACTIVE', 'SUSPENDED'];

/** The role's label in the active language; a code this app does not know is shown as sent. */
export function roleLabel(t: TFunction, role: string | undefined): string {
  if (!role) {
    return '';
  }
  return ROLES.includes(role) ? t(`auth.profile.role.${role}`) : role;
}

/** The account status's label in the active language; an unknown code is shown as sent, none as ''. */
export function statusLabel(t: TFunction, status: string | undefined): string {
  if (!status) {
    return '';
  }
  return STATUSES.includes(status) ? t(`auth.profile.status.${status}`) : status;
}
