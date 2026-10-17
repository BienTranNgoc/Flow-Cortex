/** Primary navigation items rendered in the member app header. */
export interface PrimaryNavItem {
  readonly to: string;
  readonly label: string;
  readonly end?: boolean;
}

export const PRIMARY_NAV_ITEMS: readonly PrimaryNavItem[] = [
  { to: '/', label: 'Home', end: true },
  { to: '/workflows', label: 'Workflows' },
  { to: '/runs', label: 'Runs' },
  { to: '/integrations', label: 'Integrations' },
] as const;

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
