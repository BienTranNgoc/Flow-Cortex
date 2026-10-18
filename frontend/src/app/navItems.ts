import { demoPagesEnabled } from './featureFlags';

/** Primary navigation items rendered in the member app header. */
export interface PrimaryNavItem {
  readonly to: string;
  readonly label: string;
  readonly end?: boolean;
}

const CORE_NAV_ITEMS: readonly PrimaryNavItem[] = [
  { to: '/', label: 'Home', end: true },
  { to: '/workflows', label: 'Workflows' },
];

const DEMO_NAV_ITEMS: readonly PrimaryNavItem[] = [
  { to: '/runs', label: 'Runs' },
  { to: '/integrations', label: 'Integrations' },
];

/** Header items; the demo pages join only while their flag is on, read on every call like the flag itself. */
export function primaryNavItems(): readonly PrimaryNavItem[] {
  return demoPagesEnabled() ? [...CORE_NAV_ITEMS, ...DEMO_NAV_ITEMS] : CORE_NAV_ITEMS;
}

export interface SubNavItem {
  readonly to: string;
  readonly label: string;
}

/** Items in the Docs submenu. */
export const DOCS_SUB_ITEMS: readonly SubNavItem[] = [
  { to: '/docs', label: 'Overview' },
  { to: '/docs/api', label: 'API Reference' },
  { to: '/docs/guides', label: 'Guides' },
] as const;
