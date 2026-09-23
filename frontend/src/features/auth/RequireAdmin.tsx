import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useMeQuery } from './useAuth';

/**
 * ADMIN-only route guard — a MEMBER is bounced back to `/` rather than shown
 * a 403 page (the nav already hides the link); a direct URL visit still
 * cannot reach the page.
 */
export function RequireAdmin({ children }: { children: ReactNode }) {
  const meQuery = useMeQuery();

  if (meQuery.isLoading) {
    return <p role="status">Loading…</p>;
  }
  if (meQuery.isError || !meQuery.data || meQuery.data.role !== 'ADMIN') {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}
