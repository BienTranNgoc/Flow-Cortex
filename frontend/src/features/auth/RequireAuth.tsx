import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthToken, useSessionReady } from './useAuth';

/**
 * Route guard: no token → redirect to `/login`, remembering the page that
 * was requested so login can return there. Also what a cleared token (a 401
 * from any call, `@flow-cortex/api-client`) redirects through, since this
 * component re-renders whenever the token store changes.
 */
export function RequireAuth({ children }: Readonly<{ children: ReactNode }>) {
  const token = useAuthToken();
  const sessionReady = useSessionReady();
  const location = useLocation();

  if (!sessionReady) {
    return <output>Checking your session…</output>;
  }
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
