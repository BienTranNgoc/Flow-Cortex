import { NavLink, Outlet } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';
import { Header } from './Header';
import { Footer } from './Footer';

/** App shell: nav + signed-in user + sign-out. User administration lives in the admin SPA. */
export function Layout() {
  const meQuery = useMeQuery();
  const me = meQuery.data;

  return (
    <div className="app-shell cortex-ambient">
      <Header me={me} />
      <div className="app-content">
        <Outlet />
      </div>
      <Footer />
    </div>
  );
}
