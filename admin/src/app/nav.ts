import {
  BookOpen,
  ChatText,
  ClipboardText,
  Coins,
  Cpu,
  Cube,
  GearSix,
  Headset,
  ListChecks,
  Play,
  Pulse,
  ShieldCheck,
  Sparkle,
  SquaresFour,
  Stack,
  UserGear,
  Users,
  Waveform,
  type Icon,
} from '@phosphor-icons/react';
import { can, type Caller, type PermissionKey } from './permissions';

/** A live source `useLiveNav` reads into an entry's `badge`; it also picks the count's wording. */
export type NavLiveCount = 'openSupportRequests';

/**
 * `label` and `tag` are i18n keys; the sidebar translates them. A `badge` above 0 shows a count.
 * `key` is the permission an entry and its route need; a disabled entry has none.
 */
export interface NavItem {
  label: string;
  path: string;
  icon: Icon;
  key?: PermissionKey;
  disabled?: boolean;
  tag?: string;
  badge?: number;
  liveCount?: NavLiveCount;
}

export interface NavGroup {
  id: string;
  label: string;
  items: NavItem[];
}

const COMING_SOON = 'common.shell.nav.comingSoon';

export const NAV: NavGroup[] = [
  {
    id: 'overview',
    label: 'common.shell.nav.groups.overview',
    items: [
      {
        label: 'common.shell.nav.items.dashboard',
        path: '/dashboard',
        icon: SquaresFour,
        key: 'workflow.run.view',
      },
    ],
  },
  {
    id: 'workflow-ops',
    label: 'common.shell.nav.groups.workflowOps',
    items: [
      {
        label: 'common.shell.nav.items.workflows',
        path: '/workflows',
        icon: Cube,
        disabled: true,
        tag: COMING_SOON,
      },
      { label: 'common.shell.nav.items.runs', path: '/runs', icon: Play, key: 'workflow.run.view' },
      {
        label: 'common.shell.nav.items.approvals',
        path: '/approvals',
        icon: ListChecks,
        disabled: true,
        tag: COMING_SOON,
      },
    ],
  },
  {
    id: 'credits-billing',
    label: 'common.shell.nav.groups.creditsBilling',
    items: [
      {
        label: 'common.shell.nav.items.credits',
        path: '/credits',
        icon: Coins,
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.providers',
        path: '/providers',
        icon: Cpu,
        key: 'ai.provider.view',
      },
    ],
  },
  {
    id: 'administration',
    label: 'common.shell.nav.groups.administration',
    items: [
      {
        label: 'common.shell.nav.items.users',
        path: '/users',
        icon: Users,
        key: 'user.account.view',
      },
      {
        label: 'common.shell.nav.items.smsOutbox',
        path: '/sms-outbox',
        icon: ChatText,
        key: 'platform.sms_outbox.view',
      },
      {
        label: 'common.shell.nav.items.features',
        path: '/features',
        icon: GearSix,
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.auditLog',
        path: '/audit',
        icon: ClipboardText,
        disabled: true,
        tag: COMING_SOON,
      },
    ],
  },
  {
    id: 'system',
    label: 'common.shell.nav.groups.system',
    items: [
      {
        label: 'common.shell.nav.items.mediaJobs',
        path: '/media-jobs',
        icon: Pulse,
        key: 'workflow.run.view',
      },
      {
        label: 'common.shell.nav.items.adminAccounts',
        path: '/admin-accounts',
        icon: UserGear,
        key: 'system.admin_account.view',
      },
      {
        label: 'common.shell.nav.items.voiceQuota',
        path: '/voice-quota',
        icon: Waveform,
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.health',
        path: '/health',
        icon: ShieldCheck,
        disabled: true,
        tag: COMING_SOON,
      },
    ],
  },
  {
    id: 'support',
    label: 'common.shell.nav.groups.support',
    items: [
      {
        label: 'common.shell.nav.items.supportArticles',
        path: '/support/articles',
        icon: BookOpen,
        key: 'content.help_article.view',
      },
      {
        label: 'common.shell.nav.items.supportGroups',
        path: '/support/groups',
        icon: Stack,
        key: 'content.help_topic.view',
      },
      {
        label: 'common.shell.nav.items.supportRequests',
        path: '/support/requests',
        icon: Headset,
        key: 'support.ticket.view',
        liveCount: 'openSupportRequests',
      },
      {
        label: 'common.shell.nav.items.supportSettings',
        path: '/support/settings',
        icon: Sparkle,
        key: 'platform.setting.view',
      },
    ],
  },
];

/** Whether the caller sees `item` in the sidebar: a disabled entry carries no key and is shown to every admin. */
export function isVisible(me: Caller | undefined, item: NavItem): boolean {
  return item.key === undefined || can(me, item.key);
}

/** Enabled entries the caller may open, in sidebar order: these are also the only routable pages. */
export function reachableItems(me: Caller | undefined): NavItem[] {
  return NAV.flatMap((group) => group.items).filter(
    (item) => !item.disabled && item.key !== undefined && can(me, item.key),
  );
}
