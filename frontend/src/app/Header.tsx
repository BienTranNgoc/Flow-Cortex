import { useTranslation } from 'react-i18next';
import { Link, NavLink, useLocation } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useBalance } from '../features/credits/useBalance';
import { FlowCortexLogo, ThemeToggle } from '@flow-cortex/ui-kit';
import { AppLanguageToggle } from '../lib/AppLanguageToggle';
import { formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';
import { useTheme } from './useTheme';
import { DocsMenu } from './DocsMenu';
import { primaryNavItems } from './navItems';
import { UserMenu } from './UserMenu';

const navClass = ({ isActive }: { isActive: boolean }) =>
  isActive ? 'cortex-header__nav-link is-active' : 'cortex-header__nav-link';

/** Rendered only inside `Layout`, which sits behind `RequireAuth` — the user is always signed in here. */
export function Header() {
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const credits = useBalance();
  const displayName = meQuery.data?.name;
  const email = meQuery.data?.email;
  const location = useLocation();
  const { theme, toggle } = useTheme();
  return (
    <header className="cortex-header">
      <div className="cortex-header__inner">
        <Link to="/" className="cortex-header__brand" aria-label={t('common.nav.homeLink')}>
          <FlowCortexLogo size={34} />
          <span className="cortex-header__brand-name">FlowCortex</span>
        </Link>

        <nav className="cortex-header__nav" aria-label={t('common.nav.label')}>
          {primaryNavItems(t).map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.end ?? false}
              className={navClass}
            >
              {item.label}
            </NavLink>
          ))}
          <DocsMenu pathname={location.pathname} />
        </nav>

        <div className="cortex-header__actions">
          {credits !== undefined && (
            <span className="cortex-gpu-pill">
              <svg
                xmlns="http://www.w3.org/2000/svg"
                viewBox="0 0 24 24"
                width="12"
                height="12"
                fill="currentColor"
                aria-hidden="true"
              >
                <path d="M4 6h16v2H4zm0 5h16v2H4zm0 5h16v2H4z" />
              </svg>
              <span>
                {t('common.nav.credits', { amount: formatNumber(credits, activeLocale()) })}
              </span>
            </span>
          )}
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
          <UserMenu name={displayName} email={email} />
        </div>
      </div>
    </header>
  );
}
