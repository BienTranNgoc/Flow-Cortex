import { ErrorBoundary, NodiMark, ThemeToggle } from '@flow-cortex/ui-kit';
import { useEffect, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, Outlet, useLocation } from 'react-router-dom';
import { useTheme } from '../../app/useTheme';
import { AppLanguageToggle } from '../../lib/AppLanguageToggle';
import { LegalIndexLinks } from './LegalIndexLinks';
import type { LegalOutletContext } from './legalTitle';
import { useLegalIndex } from './useLegal';
import './legal.css';

/**
 * Public frame of the legal pages: brand, language and theme toggles, the index as navigation and a
 * contained page area. Sits outside the sign-in gate, so a signed-out reader sees the same pages.
 */
export function LegalLayout() {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const { theme, toggle } = useTheme();
  const index = useLegalIndex();
  const [pageTitle, setPageTitle] = useState<string | null>(null);
  const mainRef = useRef<HTMLElement>(null);
  const previousPath = useRef(pathname);
  const brand = t('common.nav.brand');
  const hasNavigation = (index.data?.items.length ?? 0) > 0;

  useEffect(() => {
    const previous = document.title;
    document.title = `${pageTitle ?? t('legal.nav.label')} · ${brand}`;
    return () => {
      document.title = previous;
    };
  }, [pageTitle, brand, t]);

  // Only a path change moves focus, so the first render keeps the browser's own start.
  useEffect(() => {
    if (previousPath.current === pathname) return;
    previousPath.current = pathname;
    mainRef.current?.focus();
  }, [pathname]);

  const context = useMemo<LegalOutletContext>(() => ({ setTitle: setPageTitle }), []);

  return (
    <div className="legal-shell">
      <a
        href="#legal-main"
        className="skip-link"
        onClick={(event) => {
          event.preventDefault();
          mainRef.current?.focus();
        }}
      >
        {t('common.shell.skipToContent')}
      </a>
      <header className="legal-header">
        <Link to="/" className="legal-header__brand" aria-label={t('common.nav.homeLink')}>
          <NodiMark size={28} />
          <span>{brand}</span>
        </Link>
        <div className="legal-header__actions">
          <AppLanguageToggle />
          <ThemeToggle
            theme={theme}
            onToggle={toggle}
            compact
            labels={{
              switchToLight: t('common.theme.switchToLight'),
              switchToDark: t('common.theme.switchToDark'),
              light: t('common.theme.light'),
              dark: t('common.theme.dark'),
            }}
          />
        </div>
      </header>
      <div className="legal-frame">
        {hasNavigation && (
          <nav className="legal-nav" aria-label={t('legal.nav.label')}>
            <LegalIndexLinks />
          </nav>
        )}
        <main id="legal-main" ref={mainRef} className="legal-main" tabIndex={-1}>
          <ErrorBoundary
            key={pathname}
            labels={{
              title: t('common.errorView.title'),
              message: t('common.errorView.message'),
              reload: t('common.errorView.reload'),
              retry: t('common.errorView.retry'),
            }}
          >
            <Outlet context={context} />
          </ErrorBoundary>
        </main>
      </div>
    </div>
  );
}
