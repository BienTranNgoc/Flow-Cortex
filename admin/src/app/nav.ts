import type { LucideIcon } from 'lucide-react';
import {
  Activity,
  AudioWaveform,
  Boxes,
  ClipboardList,
  Coins,
  Cpu,
  LayoutDashboard,
  ListChecks,
  MessageSquare,
  Play,
  Settings2,
  ShieldCheck,
  Users,
} from 'lucide-react';
import { can, type Action } from './permissions';

/** `label` and `tag` are i18n keys; the sidebar translates them. */
export interface NavItem {
  label: string;
  path: string;
  icon: LucideIcon;
  action: Action;
  disabled?: boolean;
  tag?: string;
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
        icon: LayoutDashboard,
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
        icon: Boxes,
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
        icon: MessageSquare,
        action: 'users.manage',
      },
      {
        label: 'common.shell.nav.items.features',
        path: '/features',
        icon: Settings2,
        action: 'features.toggle',
        disabled: true,
        tag: COMING_SOON,
      },
      {
        label: 'common.shell.nav.items.auditLog',
        path: '/audit',
        icon: ClipboardList,
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
        icon: Activity,
        action: 'mediaJobs.view',
      },
      {
        label: 'common.shell.nav.items.voiceQuota',
        path: '/voice-quota',
        icon: AudioWaveform,
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
];

/** Enabled entries the role may open, in sidebar order: these are also the only routable pages. */
export function reachableItems(role: string | undefined): NavItem[] {
  return NAV.flatMap((group) => group.items).filter(
    (item) => !item.disabled && can(role, item.action),
  );
}
