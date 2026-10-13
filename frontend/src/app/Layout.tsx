import { Outlet } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { Header } from './Header';
import { Footer } from './Footer';

/** App shell: nav + signed-in user + sign-out. User administration lives in the admin SPA. */
export function Layout() {
  const meQuery = useMeQuery();

  return (
    <div className="app-shell cortex-ambient">
      <Header email={meQuery.data?.email} />
      <div className="app-content">
        <Outlet />
      </div>
      <Footer />
    </div>
  );
}
