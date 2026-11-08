import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation } from 'react-router-dom';
import { Footer } from './Footer';
import { routeFocusTarget } from './routeFocus';
import { Sidebar } from './Sidebar';
import { useIsDesktop, useSidebarMode } from './sidebarMode';
import { Topbar } from './Topbar';

/** Signed-in shell: sidebar, topbar, page canvas and footer. User administration lives in the admin SPA. */
export function Layout() {
  const { t } = useTranslation();
  const { mode, toggle } = useSidebarMode();
  const isDesktop = useIsDesktop();
  const [drawerOpen, setDrawerOpen] = useState(false);
  const mainRef = useRef<HTMLElement>(null);
  const { pathname, state } = useLocation();
  const previousPath = useRef(pathname);

  useEffect(() => {
    if (isDesktop && drawerOpen) {
      setDrawerOpen(false);
      mainRef.current?.focus();
    }
  }, [isDesktop, drawerOpen]);

  // Only a path change moves focus: not the first render, nor a page clearing its own router state.
  useEffect(() => {
    if (previousPath.current === pathname) return;
    previousPath.current = pathname;
    const main = mainRef.current;
    if (!main) return;
    const heading = main.querySelector('h1');
    const target = routeFocusTarget({ state, hasHeading: heading !== null });
    if (target === 'page') return;
    if (target === 'heading' && heading) {
      if (!heading.hasAttribute('tabindex')) heading.setAttribute('tabindex', '-1');
      heading.focus();
    } else {
      main.focus();
    }
  }, [pathname, state]);

  return (
    <div className="app-shell" data-sidebar={isDesktop ? mode : 'drawer'}>
      <a
        href="#main"
        className="skip-link"
        onClick={(event) => {
          event.preventDefault();
          mainRef.current?.focus();
        }}
      >
        {t('common.shell.skipToContent')}
      </a>
      <Sidebar
        isDesktop={isDesktop}
        mode={mode}
        onToggleMode={toggle}
        drawerOpen={drawerOpen}
        onDrawerOpenChange={setDrawerOpen}
      />
      <div className="app-column">
        <Topbar
          showMenuButton={!isDesktop}
          drawerOpen={drawerOpen}
          onOpenMenu={() => setDrawerOpen(true)}
        />
        <main id="main" ref={mainRef} className="app-main" tabIndex={-1}>
          <Outlet />
        </main>
        <Footer />
      </div>
    </div>
  );
}
