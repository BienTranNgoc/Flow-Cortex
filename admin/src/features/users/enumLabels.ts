import type { TFunction } from 'i18next';

const ROLES: readonly string[] = ['ADMIN', 'MEMBER', 'CREATOR'];
const STATUSES: readonly string[] = ['ACTIVE', 'SUSPENDED'];

/** The role's label in the active language; a code this console does not know is shown as sent. */
export function roleLabel(t: TFunction, role: string | undefined): string {
  if (!role) {
    return '';
  }
  return ROLES.includes(role) ? t(`users.role.${role}`) : role;
}

/** The account status's label in the active language; no status reads as active, an unknown code is shown as sent. */
export function statusLabel(t: TFunction, status: string | undefined): string {
  const code = status || 'ACTIVE';
  return STATUSES.includes(code) ? t(`users.status.${code}`) : code;
}
