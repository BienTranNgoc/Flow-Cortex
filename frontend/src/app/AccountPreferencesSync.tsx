import { useAuthToken } from '@flow-cortex/api-client';
import { localeOf, switchLocale, SUPPORTED_LOCALES } from '@flow-cortex/i18n';
import { useEffect, useRef } from 'react';
import { useTranslation } from 'react-i18next';
import { accountChoiceToApply } from '../features/auth/accountChoice';
import { useAccountPreferencesQuery } from '../features/auth/useAccountPreferences';
import type { SidebarMode } from './sidebarMode';
import type { Theme } from './useTheme';

const THEMES: readonly Theme[] = ['light', 'dark'];
const SIDEBAR_MODES: readonly SidebarMode[] = ['pinned', 'rail'];

interface SyncProps {
  readonly theme: Theme;
  readonly setTheme: (next: Theme) => void;
  readonly sidebarMode: SidebarMode;
  readonly setSidebarMode: (next: SidebarMode) => void;
}

/**
 * Renders nothing. The first preferences answer of a signed-in session replaces any device choice
 * that differs from it; later answers (token refreshes, this device's own saves) are never re-applied.
 */
export function AccountPreferencesSync({
  theme,
  setTheme,
  sidebarMode,
  setSidebarMode,
}: Readonly<SyncProps>) {
  const { i18n } = useTranslation();
  const token = useAuthToken();
  const { data } = useAccountPreferencesQuery();
  const applied = useRef(false);

  useEffect(() => {
    if (!token) {
      applied.current = false;
      return;
    }
    if (!data || applied.current) return;
    applied.current = true;

    const locale = accountChoiceToApply(data.locale, localeOf(i18n), SUPPORTED_LOCALES);
    if (locale) switchLocale(i18n, locale);
    const nextTheme = accountChoiceToApply(data.ui.theme, theme, THEMES);
    if (nextTheme) setTheme(nextTheme);
    const nextMode = accountChoiceToApply(data.ui.sidebarMode, sidebarMode, SIDEBAR_MODES);
    if (nextMode) setSidebarMode(nextMode);
  }, [data, token, i18n, theme, setTheme, sidebarMode, setSidebarMode]);

  return null;
}
