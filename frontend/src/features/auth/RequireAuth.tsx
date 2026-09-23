import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthToken } from './useAuth';

/**
 * Route guard: no token → redirect to `/login`, remembering the page that
 * was requested so login can return there. Also what a cleared token (a 401
 * from any call, `src/api/client.ts`) redirects through, since this
 * component re-renders whenever the token store changes.
 */
export function RequireAuth({ children }: { children: ReactNode }) {
  const token = useAuthToken();
  const location = useLocation();

  if (!token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  return <>{children}</>;
}
