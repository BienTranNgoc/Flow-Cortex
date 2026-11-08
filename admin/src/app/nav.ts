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
  Users,
  Waveform,
  type Icon,
} from '@phosphor-icons/react';
import { can, type Action } from './permissions';

/** A live source `useLiveNav` reads into an entry's `badge`; it also picks the count's wording. */
export type NavLiveCount = 'openSupportRequests';

/** `label` and `tag` are i18n keys; the sidebar translates them. A `badge` above 0 shows a count. */
export interface NavItem {
  label: string;
  path: string;
  icon: Icon;
  action: Action;
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
        action: 'runs.view',
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
        action: 'workflows.view',
        disabled: true,
        tag: COMING_SOON,
      },
      { label: 'common.shell.nav.items.runs', path: '/runs', icon: Play, action: 'runs.view' },
      {
        label: 'common.shell.nav.items.approvals',
        path: '/approvals',
        icon: ListChecks,
        action: 'approvals.view',
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
        action: 'credits.view',
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.providers',
        path: '/providers',
        icon: Cpu,
        action: 'providers.view',
        disabled: true,
        tag: COMING_SOON,
      },
    ],
  },
  {
    id: 'administration',
    label: 'common.shell.nav.groups.administration',
    items: [
      { label: 'common.shell.nav.items.users', path: '/users', icon: Users, action: 'users.manage' },
      {
        label: 'common.shell.nav.items.smsOutbox',
        path: '/sms-outbox',
        icon: ChatText,
        action: 'users.manage',
      },
      {
        label: 'common.shell.nav.items.features',
        path: '/features',
        icon: GearSix,
        action: 'features.toggle',
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.auditLog',
        path: '/audit',
        icon: ClipboardText,
        action: 'audit.view',
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
        action: 'mediaJobs.view',
      },
      {
        label: 'common.shell.nav.items.voiceQuota',
        path: '/voice-quota',
        icon: Waveform,
        action: 'voice.view',
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.health',
        path: '/health',
        icon: ShieldCheck,
        action: 'health.view',
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
        action: 'support.manage',
      },
      {
        label: 'common.shell.nav.items.supportGroups',
        path: '/support/groups',
        icon: Stack,
        action: 'support.manage',
      },
      {
        label: 'common.shell.nav.items.supportRequests',
        path: '/support/requests',
        icon: Headset,
        action: 'support.manage',
        liveCount: 'openSupportRequests',
      },
      {
        label: 'common.shell.nav.items.supportSettings',
        path: '/support/settings',
        icon: Sparkle,
        action: 'support.manage',
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
