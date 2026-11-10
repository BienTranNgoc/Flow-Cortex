import { ThemeToggle } from '@flow-cortex/ui-kit';
import { List } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { useMeQuery } from '../features/auth/useAuth';
import { useBalance } from '../features/credits/useBalance';
import { WalletBadge } from '../features/credits/WalletBadge';
import { AppLanguageToggle } from '../lib/AppLanguageToggle';
import { useSaveLocale } from '../features/auth/useAccountPreferences';
import type { Theme } from './useTheme';
import { UserMenu } from './UserMenu';
import './Topbar.css';

interface TopbarProps {
  /** Below 1024px the sidebar lives in a drawer that this button opens. */
  readonly showMenuButton: boolean;
  readonly drawerOpen: boolean;
  readonly onOpenMenu: () => void;
  /** The shell owns the one theme instance; the topbar only shows and flips it. */
  readonly theme: Theme;
  readonly onToggleTheme: () => void;
}

/** Rendered only inside `Layout`, which sits behind `RequireAuth` — the user is always signed in here. */
export function Topbar({
  showMenuButton,
  drawerOpen,
  onOpenMenu,
  theme,
  onToggleTheme,
}: Readonly<TopbarProps>) {
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const credits = useBalance();
  const saveLocale = useSaveLocale();
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
        {credits !== undefined && <WalletBadge wallet={credits} />}
        <AppLanguageToggle onSwitch={saveLocale.mutate} />
        <ThemeToggle
          theme={theme}
          onToggle={onToggleTheme}
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
