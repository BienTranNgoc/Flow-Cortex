import { NavLink, Outlet } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';

/**
 * App shell: nav + signed-in user + sign-out. MEMBER sees no Users link
 * (task 4.7, J-6) — `RequireAdmin` on the route itself is the actual guard;
 * this is just so a MEMBER never sees a link to somewhere they can't go.
 */
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
          {me?.role === 'ADMIN' && <NavLink to="/users">Users</NavLink>}
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
