import { ErrorBoundary, ThemeToggle } from '@flow-cortex/ui-kit';
import {
  CaretDown,
  Copy,
  Envelope,
  List,
  ShieldCheck,
  SignOut,
  Trash,
  UserCircle,
} from '@phosphor-icons/react';
import { useCallback, useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Outlet, useLocation } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';
import { roleLabel } from '../features/users/enumLabels';
import { AppLanguageToggle } from '../lib/AppLanguageToggle';
import { Sidebar } from './Sidebar';
import { useIsDesktop } from './useIsDesktop';
import { useLiveNav } from './useLiveNav';
import { useTheme } from './useTheme';

const SIDEBAR_COLLAPSED_KEY = 'flow-cortex:admin:sidebar:collapsed';

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * Console shell: flat topbar, sidebar nav, main content wrapped in an
 * ErrorBoundary keyed by path so navigating away clears the failed view.
 */
export function AdminLayout() {
  const me = useMeQuery().data;
  const nav = useLiveNav(me);
  const { pathname } = useLocation();
  const { t } = useTranslation();
  const { theme, toggle: toggleTheme } = useTheme();
  const [menuOpen, setMenuOpen] = useState(false);
  const [collapsed, setCollapsed] = useState<boolean>(() =>
    typeof window === 'undefined' ? false : readCollapsed(),
  );
  const [drawerOpen, setDrawerOpen] = useState(false);
  const isDesktop = useIsDesktop();
  const accountDialogRef = useRef<HTMLDialogElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const drawerButtonRef = useRef<HTMLButtonElement>(null);
  const mainRef = useRef<HTMLElement>(null);
  const displayName = me?.name?.trim() || me?.email || t('common.account.fallbackName');
  const avatarInitial = displayName.trim().charAt(0).toLocaleUpperCase();

  const closeDrawer = useCallback(() => {
    setDrawerOpen(false);
    drawerButtonRef.current?.focus();
  }, []);

  useEffect(() => {
    if (isDesktop && drawerOpen) {
      setDrawerOpen(false);
      mainRef.current?.focus();
    }
  }, [isDesktop, drawerOpen]);

  useEffect(() => {
    try {
      localStorage.setItem(SIDEBAR_COLLAPSED_KEY, collapsed ? '1' : '0');
    } catch {
      // ignore — the in-memory state is still the source of truth for the session.
    }
  }, [collapsed]);

  useEffect(() => {
    if (!menuOpen) {
      return;
    }
    menuRef.current?.querySelector<HTMLButtonElement>('[role="menuitem"]:not(:disabled)')?.focus();
    const closeOutside = (event: PointerEvent) => {
      if (
        !menuRef.current?.contains(event.target as Node) &&
        !triggerRef.current?.contains(event.target as Node)
      ) {
        setMenuOpen(false);
      }
    };
    document.addEventListener('pointerdown', closeOutside);
    return () => document.removeEventListener('pointerdown', closeOutside);
  }, [menuOpen]);

  useEffect(() => {
    const dialog = accountDialogRef.current;
    if (!dialog) {
      return;
    }
    const handleBackdropClick = (event: MouseEvent) => {
      if (event.target === dialog) {
        dialog.close();
      }
    };
    dialog.addEventListener('click', handleBackdropClick);
    return () => dialog.removeEventListener('click', handleBackdropClick);
  }, []);

  const onMenuKeyDown = (event: KeyboardEvent<HTMLDivElement>) => {
    if (event.key === 'Escape') {
      event.preventDefault();
      setMenuOpen(false);
      triggerRef.current?.focus();
      return;
    }
    if (!['ArrowDown', 'ArrowUp', 'Home', 'End'].includes(event.key)) {
      return;
    }
    const items = Array.from(
      menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ?? [],
    );
    if (items.length === 0) {
      return;
    }
    event.preventDefault();
    const currentIndex = items.indexOf(document.activeElement as HTMLButtonElement);
    let nextIndex: number;
    if (event.key === 'Home') {
      nextIndex = 0;
    } else if (event.key === 'End') {
      nextIndex = items.length - 1;
    } else if (event.key === 'ArrowDown') {
      nextIndex = (currentIndex + 1) % items.length;
    } else {
      nextIndex = (currentIndex - 1 + items.length) % items.length;
    }
    items[nextIndex]?.focus();
  };

  return (
    <div className="cortex-admin-shell">
      <header className="cortex-admin-header">
        <div className="cortex-admin-header__inner">
          <button
            ref={drawerButtonRef}
            type="button"
            className="cortex-admin-header__menu"
            aria-label={t('common.shell.nav.openMenu')}
            aria-haspopup="dialog"
            aria-expanded={drawerOpen}
            onClick={() => setDrawerOpen(true)}
          >
            <List aria-hidden="true" size={20} />
          </button>
          <div className="cortex-admin-header__actions">
            <AppLanguageToggle />
            <ThemeToggle
              theme={theme}
              onToggle={toggleTheme}
              compact
              labels={{
                switchToLight: t('common.theme.switchToLight'),
                switchToDark: t('common.theme.switchToDark'),
                light: t('common.theme.light'),
                dark: t('common.theme.dark'),
              }}
            />
            {me && (
              <div className="cortex-admin-user-menu">
                <button
                  ref={triggerRef}
                  type="button"
                  className="cortex-admin-user-menu__trigger"
                  aria-label={t('common.account.trigger', { name: displayName })}
                  aria-haspopup="menu"
                  aria-expanded={menuOpen}
                  aria-controls="cortex-admin-account-menu"
                  onClick={() => setMenuOpen((open) => !open)}
                >
                  <span className="cortex-admin-user-menu__avatar" aria-hidden="true">
                    {avatarInitial}
                    <span className="cortex-admin-user-menu__presence" aria-hidden="true" />
                  </span>
                  <span className="cortex-admin-user-menu__name">{displayName}</span>
                  <CaretDown
                    aria-hidden="true"
                    size={14}
                    className="cortex-admin-user-menu__chevron"
                  />
                </button>
                {menuOpen && (
                  <div
                    ref={menuRef}
                    id="cortex-admin-account-menu"
                    className="cortex-admin-user-menu__panel"
                    role="menu"
                    tabIndex={-1}
                    aria-label={t('common.account.menu')}
                    onKeyDown={onMenuKeyDown}
                    onBlur={(event) => {
                      if (
                        !(event.relatedTarget instanceof Node) ||
                        !event.currentTarget.contains(event.relatedTarget)
                      ) {
                        setMenuOpen(false);
                      }
                    }}
                  >
                    <div className="cortex-admin-user-menu__identity">
                      <span
                        className="cortex-admin-user-menu__avatar cortex-admin-user-menu__avatar--lg"
                        aria-hidden="true"
                      >
                        {avatarInitial}
                        <span className="cortex-admin-user-menu__presence" aria-hidden="true" />
                      </span>
                      <div className="cortex-admin-user-menu__identity-text">
                        <strong>{displayName}</strong>
                        <span>{me.email}</span>
                        <span className="cortex-admin-user-menu__role-pill">
                          <ShieldCheck aria-hidden="true" size={11} />
                          {roleLabel(t, me.role)}
                        </span>
                      </div>
                    </div>

                    <button
                      type="button"
                      role="menuitem"
                      className="cortex-admin-user-menu__item"
                      onClick={() => {
                        setMenuOpen(false);
                        accountDialogRef.current?.showModal();
                      }}
                    >
                      <UserCircle aria-hidden="true" size={20} />
                      <span>{t('common.account.details')}</span>
                    </button>

                    <div className="cortex-admin-user-menu__divider" aria-hidden="true" />

                    <button
                      type="button"
                      role="menuitem"
                      className="cortex-admin-user-menu__item cortex-admin-user-menu__item--danger"
                      onClick={() => {
                        setMenuOpen(false);
                        void logout();
                      }}
                    >
                      <SignOut aria-hidden="true" size={20} />
                      <span>{t('common.account.signOut')}</span>
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </header>
      <Sidebar
        me={me}
        collapsed={collapsed}
        onToggle={() => setCollapsed((value) => !value)}
        drawerOpen={drawerOpen}
        onDrawerClose={closeDrawer}
        nav={nav}
      />
      <main ref={mainRef} tabIndex={-1} className="cortex-admin-content">
        <ErrorBoundary
          key={pathname}
          labels={{
            title: t('common.errorView.title'),
            message: t('common.errorView.message'),
            reload: t('common.errorView.reload'),
          }}
        >
          <Outlet />
        </ErrorBoundary>
      </main>
      {me && (
        <dialog
          ref={accountDialogRef}
          className="cortex-admin-account-dialog"
          aria-label={t('common.account.dialogTitle')}
          onClose={() => {
            triggerRef.current?.focus();
          }}
        >
          <div className="cortex-admin-account-dialog__cover" aria-hidden="true" />
          <header className="cortex-admin-account-dialog__header">
            <span
              className="cortex-admin-account-dialog__avatar"
              aria-hidden="true"
            >
              {avatarInitial}
              <span className="cortex-admin-account-dialog__presence" aria-hidden="true" />
            </span>
            <div className="cortex-admin-account-dialog__identity">
              <div className="cortex-admin-account-dialog__name-row">
                <h2 id="cortex-admin-account-dialog-title">{displayName}</h2>
                <span className="cortex-admin-account-dialog__role-pill">
                  <ShieldCheck aria-hidden="true" size={12} />
                  {roleLabel(t, me.role)}
                </span>
              </div>
              <div className="cortex-admin-account-dialog__email-row">
                <Envelope aria-hidden="true" size={13} />
                <span>{me.email ?? '—'}</span>
                <button
                  type="button"
                  className="cortex-admin-account-dialog__copy"
                  onClick={() => {
                    if (me.email) void navigator.clipboard.writeText(me.email);
                  }}
                  aria-label={t('common.account.copyEmail')}
                  title={t('common.account.copyEmailShort')}
                >
                  <Copy aria-hidden="true" size={12} />
                </button>
              </div>
            </div>
            <button
              type="button"
              className="cortex-admin-account-dialog__close"
              onClick={() => accountDialogRef.current?.close()}
              aria-label={t('common.account.close')}
            >
              ✕
            </button>
          </header>

          <section className="cortex-admin-account-dialog__section cortex-admin-account-dialog__section--danger" aria-labelledby="cortex-admin-account-dialog-danger">
            <h3 id="cortex-admin-account-dialog-danger">{t('common.account.dangerZone')}</h3>
            <button
              type="button"
              className="cortex-admin-account-dialog__danger"
              disabled
              title={t('common.account.deleteHint')}
            >
              <Trash aria-hidden="true" size={15} />
              <span>{t('common.account.delete')}</span>
              <span className="cortex-admin-account-dialog__action-meta">
                {t('common.account.deleteDisabled')}
              </span>
            </button>
          </section>
        </dialog>
      )}
    </div>
  );
}
