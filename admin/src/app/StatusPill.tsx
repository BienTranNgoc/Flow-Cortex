import type { ReactNode } from 'react';

export type StatusTone = 'success' | 'warning' | 'error' | 'info' | 'muted';

interface StatusPillProps {
  readonly tone?: StatusTone;
  readonly label: string;
  readonly dot?: boolean;
  readonly children?: ReactNode;
}

/** Pill-shaped status indicator. Dot optional for compact, busy lists. */
export function StatusPill({ tone = 'info', label, dot, children }: Readonly<StatusPillProps>) {
  return (
    <span className={`cortex-admin-pill cortex-admin-pill--${tone}`}>
      {dot && <span className="cortex-admin-pill__dot" aria-hidden="true" />}
      <span>{children ?? label}</span>
    </span>
  );
}
