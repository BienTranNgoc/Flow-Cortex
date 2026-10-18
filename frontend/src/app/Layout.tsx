import { Outlet } from 'react-router-dom';
import { Header } from './Header';
import { Footer } from './Footer';

/** App shell: nav + avatar menu + footer. User administration lives in the admin SPA. */
export function Layout() {
  return (
    <div className="app-shell cortex-ambient">
      <Header />
      <div className="app-content">
        <Outlet />
      </div>
      <Footer />
    </div>
  );
}
