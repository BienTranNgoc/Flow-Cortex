import type { ReactNode } from 'react';
import { Navigate } from 'react-router-dom';
import { useMeQuery } from './useAuth';

/** ADMIN-only route guard: a MEMBER (or a direct URL visit) is bounced to `/`; the nav already hides the link. */
export function RequireAdmin({ children }: Readonly<{ children: ReactNode }>) {
  const meQuery = useMeQuery();

  if (meQuery.isLoading) {
    return <output>Loading…</output>;
  }
  if (meQuery.isError || meQuery.data?.role !== 'ADMIN') {
    return <Navigate to="/" replace />;
  }
  return <>{children}</>;
}
