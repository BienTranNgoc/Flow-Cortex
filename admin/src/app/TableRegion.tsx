import type { ReactNode } from 'react';

/** Horizontally scrollable, keyboard-focusable region around an admin table. */
export function TableRegion({
  label,
  busy,
  children,
}: Readonly<{ label: string; busy?: boolean; children: ReactNode }>) {
  return (
    <div
      className="cortex-admin-table-wrapper"
      role="region"
      aria-label={label}
      aria-busy={busy}
      tabIndex={0}
    >
      {children}
    </div>
  );
}
