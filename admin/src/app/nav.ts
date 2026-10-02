import { can, type Action } from './permissions';

export interface NavItem {
  label: string;
  path: string;
  icon: string;
  action: Action;
  disabled?: boolean;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  {
    label: 'Operations',
    items: [
      { label: 'Runs', path: '/runs', icon: '▶', action: 'runs.view' },
      { label: 'Media jobs', path: '/media-jobs', icon: '◆', action: 'mediaJobs.view' },
      { label: 'Providers', path: '/providers', icon: '◇', action: 'runs.view', disabled: true },
      { label: 'Usage', path: '/usage', icon: '▤', action: 'runs.view', disabled: true },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Users', path: '/users', icon: '●', action: 'users.manage' },
      { label: 'Settings', path: '/settings', icon: '⚙', action: 'users.manage', disabled: true },
      { label: 'Audit log', path: '/audit', icon: '☰', action: 'users.manage', disabled: true },
    ],
  },
];

/** Enabled entries the role may open, in sidebar order: these are also the only routable pages. */
export function reachableItems(role: string | undefined): NavItem[] {
  return NAV.flatMap((group) => group.items).filter((item) => !item.disabled && can(role, item.action));
}
