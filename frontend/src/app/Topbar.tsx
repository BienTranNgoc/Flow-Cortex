import { ThemeToggle } from '@flow-cortex/ui-kit';
import { List } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { useMeQuery } from '../features/auth/useAuth';
import { useBalance } from '../features/credits/useBalance';
import { AppLanguageToggle } from '../lib/AppLanguageToggle';
import { formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';
import { useTheme } from './useTheme';
import { UserMenu } from './UserMenu';
import './Topbar.css';

interface TopbarProps {
  /** Below 1024px the sidebar lives in a drawer that this button opens. */
  readonly showMenuButton: boolean;
  readonly drawerOpen: boolean;
  readonly onOpenMenu: () => void;
}

/** Rendered only inside `Layout`, which sits behind `RequireAuth` — the user is always signed in here. */
export function Topbar({ showMenuButton, drawerOpen, onOpenMenu }: Readonly<TopbarProps>) {
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const credits = useBalance();
  const { theme, toggle } = useTheme();
  return (
    <header className="topbar">
      {showMenuButton && (
        <button
          type="button"
          className="topbar__menu"
          aria-label={t('common.nav.openMenu')}
          aria-haspopup="dialog"
          aria-expanded={drawerOpen}
          onClick={onOpenMenu}
        >
          <List size={20} aria-hidden="true" />
        </button>
      )}
      <div className="topbar__actions">
        {credits !== undefined && (
          <span className="topbar__credits">
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
            <span>{t('common.nav.credits', { amount: formatNumber(credits, activeLocale()) })}</span>
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
        <UserMenu name={meQuery.data?.name} email={meQuery.data?.email} />
      </div>
    </header>
  );
}
