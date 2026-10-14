import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { Outlet, useLocation } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';
import { Sidebar } from './Sidebar';

/** Console shell. The page area is keyed by path so navigating away from a failed page clears it. */
export function AdminLayout() {
  const me = useMeQuery().data;
  const { pathname } = useLocation();

  return (
    <div className="admin-shell">
      <header className="admin-header">
        <span className="admin-title">Flow-Cortex Admin</span>
        {me && (
          <div className="admin-user">
            <span>{me.email}</span>
            <button type="button" onClick={() => void logout()}>
              Sign out
            </button>
          </div>
        )}
      </header>
      <Sidebar role={me?.role} />
      <div className="admin-content">
        <ErrorBoundary key={pathname}>
          <Outlet />
        </ErrorBoundary>
      </div>
    </div>
  );
}
