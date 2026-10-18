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

export interface NavItem {
  label: string;
  path: string;
  icon: LucideIcon;
  action: Action;
  disabled?: boolean;
  tag?: string;
}

export interface NavGroup {
  label: string;
  items: NavItem[];
}

export const NAV: NavGroup[] = [
  {
    label: 'Overview',
    items: [
      {
        label: 'Dashboard',
        path: '/dashboard',
        icon: LayoutDashboard,
        action: 'runs.view',
      },
    ],
  },
  {
    label: 'Workflow Ops',
    items: [
      {
        label: 'Workflows & Templates',
        path: '/workflows',
        icon: Boxes,
        action: 'workflows.view',
        disabled: true,
        tag: 'Coming soon',
      },
      { label: 'Runs', path: '/runs', icon: Play, action: 'runs.view' },
      {
        label: 'Approval Queue',
        path: '/approvals',
        icon: ListChecks,
        action: 'approvals.view',
        disabled: true,
        tag: 'Coming soon',
      },
    ],
  },
  {
    label: 'Credits & Billing',
    items: [
      {
        label: 'Credits & Wallets',
        path: '/credits',
        icon: Coins,
        action: 'credits.view',
        disabled: true,
        tag: 'Coming soon',
      },
      {
        label: 'Model Providers',
        path: '/providers',
        icon: Cpu,
        action: 'providers.view',
        disabled: true,
        tag: 'Coming soon',
      },
    ],
  },
  {
    label: 'Administration',
    items: [
      { label: 'Users', path: '/users', icon: Users, action: 'users.manage' },
      { label: 'SMS Outbox', path: '/sms-outbox', icon: MessageSquare, action: 'users.manage' },
      {
        label: 'Feature Switches',
        path: '/features',
        icon: Settings2,
        action: 'features.toggle',
        disabled: true,
        tag: 'Coming soon',
      },
      {
        label: 'Audit Log',
        path: '/audit',
        icon: ClipboardList,
        action: 'audit.view',
        disabled: true,
        tag: 'Coming soon',
      },
    ],
  },
  {
    label: 'System',
    items: [
      { label: 'Media Jobs', path: '/media-jobs', icon: Activity, action: 'mediaJobs.view' },
      {
        label: 'Voice Quota',
        path: '/voice-quota',
        icon: AudioWaveform,
        action: 'voice.view',
        disabled: true,
        tag: 'Coming soon',
      },
      {
        label: 'Health',
        path: '/health',
        icon: ShieldCheck,
        action: 'health.view',
        disabled: true,
        tag: 'Coming soon',
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
