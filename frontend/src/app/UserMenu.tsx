import { useEffect, useId, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { setToken, signOut } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';
import { accountStatsEnabled, demoPagesEnabled } from './featureFlags';
import './UserMenu.css';

interface UserMenuProps {
  readonly name: string | string[] | null | undefined;
  readonly email: string | null | undefined;
}

// Placeholder quota figures shown only behind the demo account-stats flag.
const DEMO_GPU_MINUTES_USED = 120;
const DEMO_GPU_MINUTES_TOTAL = 240;
const DEMO_TOKEN_BALANCE = 84_500;

function initialsFrom(name: string | string[] | null | undefined, email: string | null | undefined): string {
  const source = (typeof name === 'string' ? name : '').trim() || (email ?? '');
  const parts = source.split(/[\s@.]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

/** Round-avatar trigger in the top-right header that opens account / quota / sign-out. */
export function UserMenu({ name, email }: Readonly<UserMenuProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelId = useId();
  const navigate = useNavigate();
  const queryClient = useQueryClient();

  const initials = initialsFrom(name, email);

  useEffect(() => {
    if (!open) return;
    const onClick = (event: MouseEvent) => {
      const target = event.target as Node | null;
      if (target && rootRef.current?.contains(target)) return;
      setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('mousedown', onClick);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('mousedown', onClick);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const onSignOut = async () => {
    setOpen(false);
    setSigningOut(true);
    try {
      await signOut();
    } finally {
      setToken(null);
      queryClient.clear();
      setSigningOut(false);
      navigate('/login', { replace: true });
    }
  };

  const showStats = accountStatsEnabled();

  return (
    <div ref={rootRef} className="cortex-user-menu">
      <button
        ref={triggerRef}
        type="button"
        className="cortex-user-menu__trigger"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={email ? t('common.userMenu.openFor', { email }) : t('common.userMenu.open')}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="cortex-user-menu__avatar" aria-hidden="true">
          {initials}
        </span>
      </button>

      {open && (
        <div id={panelId} className="cortex-user-menu__panel">
          <div className="cortex-user-menu__identity">
            <span className="cortex-user-menu__avatar cortex-user-menu__avatar--lg" aria-hidden="true">
              {initials}
            </span>
            <div className="cortex-user-menu__identity-text">
              {typeof name === 'string' && name.trim().length > 0 && (
                <strong className="cortex-user-menu__name">{name}</strong>
              )}
              {email && <span className="cortex-user-menu__email">{email}</span>}
            </div>
          </div>

          {showStats && (
            <div className="cortex-user-menu__quota" aria-label={t('common.userMenu.quotas')}>
              <div className="cortex-user-menu__quota-row">
                <span>{t('common.userMenu.gpuMinutes')}</span>
                <span className="cortex-user-menu__quota-value">
                  {t('common.userMenu.quotaUsage', {
                    used: formatNumber(DEMO_GPU_MINUTES_USED, locale),
                    total: formatNumber(DEMO_GPU_MINUTES_TOTAL, locale),
                  })}
                </span>
              </div>
              <div className="cortex-user-menu__quota-row">
                <span>{t('common.userMenu.tokenBalance')}</span>
                <span className="cortex-user-menu__quota-value">
                  {formatNumber(DEMO_TOKEN_BALANCE, locale)}
                </span>
              </div>
            </div>
          )}

          <Link to="/profile" className="cortex-user-menu__item" onClick={() => setOpen(false)}>
            <span>{t('common.userMenu.accountSettings')}</span>
          </Link>
          {demoPagesEnabled() && (
            <Link
              to="/integrations"
              className="cortex-user-menu__item"
              onClick={() => setOpen(false)}
            >
              <span>{t('common.userMenu.connectedServices')}</span>
            </Link>
          )}
          <Link to="/docs" className="cortex-user-menu__item" onClick={() => setOpen(false)}>
            <span>{t('common.userMenu.helpDocs')}</span>
          </Link>

          <div className="cortex-user-menu__divider" aria-hidden="true" />

          <button
            type="button"
            className="cortex-user-menu__item cortex-user-menu__item--danger"
            onClick={onSignOut}
            disabled={signingOut}
          >
            {signingOut ? t('common.userMenu.signingOut') : t('common.nav.signOut')}
          </button>
        </div>
      )}
    </div>
  );
}
