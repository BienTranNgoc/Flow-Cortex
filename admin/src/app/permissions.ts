import type { MeResponse, MeResponsePermissionsItem } from '@flow-cortex/api-client';

export type PermissionKey = MeResponsePermissionsItem;

export type Caller = Pick<MeResponse, 'permissions' | 'adminDisabled'>;

/** Single arbiter for the sidebar, the routes and the pages: a key counts only for an admin account that is not deactivated. */
export function can(me: Caller | undefined, key: PermissionKey): boolean {
  return me !== undefined && !me.adminDisabled && me.permissions.includes(key);
}

/** The console admits a caller holding at least one key and not deactivated. */
export function canEnterConsole(me: Caller | undefined): boolean {
  return me !== undefined && !me.adminDisabled && me.permissions.length > 0;
}
