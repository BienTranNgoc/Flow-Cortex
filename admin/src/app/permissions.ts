export const ACTIONS = [
  'console.enter',
  'runs.view',
  'mediaJobs.view',
  'workflows.view',
  'approvals.view',
  'credits.view',
  'providers.view',
  'sms.view',
  'features.toggle',
  'audit.view',
  'voice.view',
  'health.view',
  'users.manage',
] as const;

export type Action = (typeof ACTIONS)[number];

/** The one table both the route guards and the sidebar read. */
const GRANTS: Record<string, readonly Action[]> = {
  ADMIN: ACTIONS,
  MEMBER: [],
};

/** Single arbiter: a role may use `action` only when listed in its grant row. */
export function can(role: string | undefined, action: Action): boolean {
  return role !== undefined && Object.hasOwn(GRANTS, role) && (GRANTS[role]?.includes(action) ?? false);
}
