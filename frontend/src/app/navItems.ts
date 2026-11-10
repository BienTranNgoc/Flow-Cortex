import { BookOpen, FlowArrow, House, PlugsConnected, Play, type Icon } from '@phosphor-icons/react';
import type { TFunction } from 'i18next';
import i18n from '../lib/i18n';
import { demoPagesEnabled } from './featureFlags';

/** Primary navigation items rendered in the member app sidebar. */
export interface PrimaryNavItem {
  readonly to: string;
  readonly label: string;
  readonly icon: Icon;
  readonly end?: boolean;
}

interface NavEntry {
  readonly to: string;
  readonly labelKey: string;
  readonly icon: Icon;
  readonly end?: boolean;
}

/** Icon of the Docs group, which is not a primary entry. */
export const DOCS_ICON: Icon = BookOpen;

const CORE_NAV_ITEMS: readonly NavEntry[] = [
  { to: '/', labelKey: 'common.nav.home', icon: House, end: true },
  { to: '/workflows', labelKey: 'common.nav.workflows', icon: FlowArrow },
  { to: '/runs', labelKey: 'common.nav.runs', icon: Play },
];

const DEMO_NAV_ITEMS: readonly NavEntry[] = [
  { to: '/integrations', labelKey: 'common.nav.integrations', icon: PlugsConnected },
];

/**
 * Sidebar items; the demo pages join only while their flag is on, read on every call like the flag itself.
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

/** Items in the Docs group; `labelKey` is translated where it renders. */
export const DOCS_SUB_ITEMS: readonly SubNavItem[] = [
  { to: '/docs', labelKey: 'common.docs.tabs.overview' },
  { to: '/docs/api', labelKey: 'common.docs.tabs.api' },
  { to: '/docs/guides', labelKey: 'common.docs.tabs.guides' },
] as const;
