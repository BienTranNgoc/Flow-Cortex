export const ACTIONS = ['console.enter', 'runs.view', 'mediaJobs.view', 'users.manage'] as const;

export type Action = (typeof ACTIONS)[number];

/** The one table both the route guards and the sidebar read. */
const GRANTS: Record<string, readonly Action[]> = {
  ADMIN: ACTIONS,
  MEMBER: [],
};

export function can(role: string | undefined, action: Action): boolean {
  return role !== undefined && Object.hasOwn(GRANTS, role) && (GRANTS[role]?.includes(action) ?? false);
}
