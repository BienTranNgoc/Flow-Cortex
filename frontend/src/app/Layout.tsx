import { NavLink, Outlet } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';

/** App shell: nav + signed-in user + sign-out. User administration lives in the admin SPA. */
export function Layout() {
  const meQuery = useMeQuery();
  const me = meQuery.data;

  return (
    <div className="app-shell">
      <header className="app-header">
        <span className="app-title">Flow-Cortex</span>
        <nav aria-label="Main navigation">
          <NavLink to="/" end>
            Home
          </NavLink>
          <NavLink to="/profile">Profile</NavLink>
          <NavLink to="/workflows">Workflows</NavLink>
        </nav>
        {me && (
          <div className="app-user">
            <span>{me.email}</span>
            <button type="button" onClick={logout}>
              Sign out
            </button>
          </div>
        )}
      </header>
      <div className="app-content">
        <Outlet />
      </div>
    </div>
  );
}
