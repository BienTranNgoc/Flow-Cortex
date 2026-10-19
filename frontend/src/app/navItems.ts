import type { TFunction } from 'i18next';
import i18n from '../lib/i18n';
import { demoPagesEnabled } from './featureFlags';

/** Primary navigation items rendered in the member app header. */
export interface PrimaryNavItem {
  readonly to: string;
  readonly label: string;
  readonly end?: boolean;
}

interface NavEntry {
  readonly to: string;
  readonly labelKey: string;
  readonly end?: boolean;
}

const CORE_NAV_ITEMS: readonly NavEntry[] = [
  { to: '/', labelKey: 'common.nav.home', end: true },
  { to: '/workflows', labelKey: 'common.nav.workflows' },
];

const DEMO_NAV_ITEMS: readonly NavEntry[] = [
  { to: '/runs', labelKey: 'common.nav.runs' },
  { to: '/integrations', labelKey: 'common.nav.integrations' },
];

/**
 * Header items; the demo pages join only while their flag is on, read on every call like the flag itself.
 * Components pass their own `t` so labels follow a language switch.
 */
export function primaryNavItems(t: TFunction = i18n.t): readonly PrimaryNavItem[] {
  const entries = demoPagesEnabled() ? [...CORE_NAV_ITEMS, ...DEMO_NAV_ITEMS] : CORE_NAV_ITEMS;
  return entries.map(({ labelKey, ...rest }) => ({ ...rest, label: t(labelKey) }));
}

export interface SubNavItem {
  readonly to: string;
  readonly labelKey: string;
}

/** Items in the Docs submenu; `labelKey` is translated where it renders. */
export const DOCS_SUB_ITEMS: readonly SubNavItem[] = [
  { to: '/docs', labelKey: 'common.docs.tabs.overview' },
  { to: '/docs/api', labelKey: 'common.docs.tabs.api' },
  { to: '/docs/guides', labelKey: 'common.docs.tabs.guides' },
] as const;
