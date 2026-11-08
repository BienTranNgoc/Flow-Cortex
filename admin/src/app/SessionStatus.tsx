import type { ReactNode } from 'react';

/** Flat centred status shown before any signed-in page. It renders ahead of the shell, so it cannot use it. */
export function SessionStatus({ children }: Readonly<{ children: ReactNode }>) {
  return (
    <div role="status" className="cortex-admin-session-status">
      {children}
    </div>
  );
}
