import type { UserResponse } from '@flow-cortex/api-client';
import { can, type Caller } from '../../app/permissions';

/**
 * Whether the console offers edit, role and suspend controls for `user`. Staff-ness is the
 * server's `staffTarget` flag, so a custom staff role is read-only here exactly as the server refuses it.
 */
export function canManageAccount(
  me: Caller | undefined,
  user: Pick<UserResponse, 'staffTarget'>,
): boolean {
  if (me === undefined || me.adminDisabled) return false;
  return !user.staffTarget || can(me, 'system.admin_account.manage');
}

/**
 * Whether the console offers to reset `user`'s second factor: it is on, the target is somebody
 * else, the caller may update accounts and, for a staff or admin target, manage admin accounts —
 * the keys the server asks for.
 */
export function canResetTotp(
  me: (Caller & { id?: string }) | undefined,
  user: Pick<UserResponse, 'id' | 'staffTarget' | 'totpEnabled'>,
): boolean {
  if (me?.id === undefined || me.id === user.id || user.totpEnabled !== true) return false;
  return canManageAccount(me, user) && can(me, 'user.account.update');
}
