import { can, type Action } from './permissions';

/** `label` is an i18n key; the sidebar translates it. */
export interface NavItem {
  label: string;
  path: string;
  icon: string;
  action: Action;
  disabled?: boolean;
}

export interface NavGroup {
  id: string;
  label: string;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  {
    id: 'overview',
    label: 'common.shell.nav.groups.overview',
    items: [
      {
        label: 'common.shell.nav.items.dashboard',
        path: '/dashboard',
        icon: '▦',
        action: 'runs.view',
      },
    ],
  },
  {
    id: 'operations',
    label: 'common.shell.nav.groups.operations',
    items: [
      { label: 'common.shell.nav.items.runs', path: '/runs', icon: '▶', action: 'runs.view' },
      {
        label: 'common.shell.nav.items.mediaJobs',
        path: '/media-jobs',
        icon: '◆',
        action: 'mediaJobs.view',
      },
      {
        label: 'common.shell.nav.items.providers',
        path: '/providers',
        icon: '◇',
        action: 'runs.view',
        disabled: true,
      },
      {
        label: 'common.shell.nav.items.usage',
        path: '/usage',
        icon: '▤',
        action: 'runs.view',
        disabled: true,
      },
    ],
  },
  {
    id: 'administration',
    label: 'common.shell.nav.groups.administration',
    items: [
      { label: 'common.shell.nav.items.users', path: '/users', icon: '●', action: 'users.manage' },
      {
        label: 'common.shell.nav.items.settings',
        path: '/settings',
        icon: '⚙',
        action: 'users.manage',
        disabled: true,
      },
      {
        label: 'common.shell.nav.items.auditLog',
        path: '/audit',
        icon: '☰',
        action: 'users.manage',
        disabled: true,
      },
    ],
  },
];

/** Enabled entries the role may open, in sidebar order: these are also the only routable pages. */
export function reachableItems(role: string | undefined): NavItem[] {
  return NAV.flatMap((group) => group.items).filter(
    (item) => !item.disabled && can(role, item.action),
  );
}
