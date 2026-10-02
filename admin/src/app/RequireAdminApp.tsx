import type { ReactNode } from 'react';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthToken, useMeQuery } from '../features/auth/useAuth';
import { NoAccessPage } from './NoAccessPage';
import { can } from './permissions';

/**
 * Entry guard of the whole console: no token → `/login` (remembering the page), a role without
 * `console.enter` → "No access". Nothing under it mounts, so a refused user triggers no admin call.
 */
export function RequireAdminApp({ children }: Readonly<{ children: ReactNode }>) {
  const token = useAuthToken();
  const location = useLocation();
  const meQuery = useMeQuery();

  if (!token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (meQuery.isPending) {
    return <output>Checking your account…</output>;
  }
  if (meQuery.isError) {
    return (
      <main className="auth-page">
        <p role="alert">Could not check your account.</p>
        <button type="button" onClick={() => meQuery.refetch()}>
          Retry
        </button>
      </main>
    );
  }
  if (!can(meQuery.data.role, 'console.enter')) {
    return <NoAccessPage email={meQuery.data.email} />;
  }
  return <>{children}</>;
}
