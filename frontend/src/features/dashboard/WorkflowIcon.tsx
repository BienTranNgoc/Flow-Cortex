import type { TFunction } from 'i18next';
import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import type { WorkflowIconKey } from './workflowIconKey';

interface IconProps {
  readonly size?: number;
  readonly title?: string;
}

interface DrawProps extends IconProps {
  readonly t: TFunction;
}

function wrap(children: ReactNode, props: DrawProps, labelKey: string): ReactNode {
  const { size = 20, title, t } = props;
  const label = title ?? t(labelKey);
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 24 24"
      width={size}
      height={size}
      fill="none"
      stroke="currentColor"
      strokeWidth={1.6}
      strokeLinecap="round"
      strokeLinejoin="round"
      role="img"
      aria-label={label}
    >
      {children}
    </svg>
  );
}

const Icons = {
  video: (p: DrawProps) =>
    wrap(
      <>
        <rect x="3" y="6" width="13" height="12" rx="2" />
        <path d="M16 10l5-3v10l-5-3z" />
      </>,
      p,
      'dashboard.icon.video',
    ),
  tiktok: (p: DrawProps) =>
    wrap(
      <>
        <path d="M14 4v9.5a3.5 3.5 0 1 1-3.5-3.5" />
        <path d="M14 4c0 2.5 2 4.5 4.5 4.5" />
      </>,
      p,
      'dashboard.icon.tiktok',
    ),
  terminal: (p: DrawProps) =>
    wrap(
      <>
        <rect x="3" y="5" width="18" height="14" rx="2" />
        <path d="M7 10l3 2-3 2M13 14h4" />
      </>,
      p,
      'dashboard.icon.terminal',
    ),
  alert: (p: DrawProps) =>
    wrap(
      <>
        <path d="M12 3l9 16H3z" />
        <path d="M12 10v4M12 17h.01" />
      </>,
      p,
      'dashboard.icon.alert',
    ),
  cart: (p: DrawProps) =>
    wrap(
      <>
        <path d="M3 4h2l2 12h12l2-8H7" />
        <circle cx="9" cy="20" r="1.4" />
        <circle cx="17" cy="20" r="1.4" />
      </>,
      p,
      'dashboard.icon.cart',
    ),
  audio: (p: DrawProps) =>
    wrap(
      <>
        <path d="M4 14V10M8 18V6M12 20V4M16 18V6M20 14V10" />
      </>,
      p,
      'dashboard.icon.audio',
    ),
  image: (p: DrawProps) =>
    wrap(
      <>
        <rect x="3" y="4" width="18" height="16" rx="2" />
        <circle cx="9" cy="10" r="2" />
        <path d="M21 16l-5-5-9 9" />
      </>,
      p,
      'dashboard.icon.image',
    ),
  spark: (p: DrawProps) =>
    wrap(
      <>
        <path d="M12 3v4M12 17v4M3 12h4M17 12h4M5.6 5.6l2.8 2.8M15.6 15.6l2.8 2.8M5.6 18.4l2.8-2.8M15.6 8.4l2.8-2.8" />
      </>,
      p,
      'dashboard.icon.spark',
    ),
} as const;

export function WorkflowIcon({ icon, ...rest }: Readonly<{ icon: WorkflowIconKey } & IconProps>) {
  const { t } = useTranslation();
  const Component = Icons[icon];
  return Component({ ...rest, t });
}
