import { Outlet } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { Header } from './Header';
import { Footer } from './Footer';

/** App shell: nav + signed-in user + sign-out. User administration lives in the admin SPA. */
export function Layout() {
  const meQuery = useMeQuery();
  const me = meQuery.data ?? null;
  // Header only needs the email; narrow to its expected shape so exactOptionalPropertyTypes
  // doesn't reject the wider MeResponse | undefined the query returns.
  const headerMe = me?.email ? { email: me.email } : null;

  return (
    <div className="app-shell cortex-ambient">
      <Header me={headerMe} />
      <div className="app-content">
        <Outlet />
      </div>
      <Footer />
    </div>
  );
}
