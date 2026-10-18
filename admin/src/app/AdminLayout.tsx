import { ErrorBoundary, FlowCortexLogo } from '@flow-cortex/ui-kit';
import {
  ChevronDown,
  Copy,
  HelpCircle,
  KeyRound,
  LogOut,
  Mail,
  PanelLeft,
  PanelRight,
  ShieldCheck,
  Trash2,
  UserCog,
  UserCircle2,
  Users,
} from 'lucide-react';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';
import { Sidebar } from './Sidebar';
import { ThemeToggle } from './ThemeToggle';

const SIDEBAR_COLLAPSED_KEY = 'flow-cortex:admin:sidebar:collapsed';

function readCollapsed(): boolean {
  try {
    return localStorage.getItem(SIDEBAR_COLLAPSED_KEY) === '1';
  } catch {
    return false;
  }
}

/**
 * Console shell: glass header, sidebar nav, main content wrapped in an
 * ErrorBoundary keyed by path so navigating away clears the failed view.
 */
export function AdminLayout() {
  const me = useMeQuery().data;
  const { pathname } = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const [collapsed, setCollapsed] = useState<boolean>(() =>
    typeof window === 'undefined' ? false : readCollapsed(),
  );
  const accountDialogRef = useRef<HTMLDialogElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const displayName = me?.name?.trim() || me?.email || 'Account';
  const avatarInitial = displayName.trim().charAt(0).toLocaleUpperCase();

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
            type="button"
            className="cortex-admin-header__collapse-toggle"
            aria-pressed={collapsed}
            aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
            onClick={() => setCollapsed((value) => !value)}
          >
            {collapsed ? (
              <PanelLeft aria-hidden="true" size={16} />
            ) : (
              <PanelRight aria-hidden="true" size={16} />
            )}
          </button>
          <a className="cortex-admin-header__brand" href="/dashboard" aria-label="FlowCortex admin home">
            <span className="cortex-admin-header__brand-mark" aria-hidden="true">
              <FlowCortexLogo size={22} />
            </span>
            <span className="cortex-admin-header__brand-name">FlowCortex</span>
            <span className="cortex-admin-header__title">Admin console</span>
          </a>
          <div className="cortex-admin-header__actions">
            <ThemeToggle compact />
            {me && (
              <div className="cortex-admin-user-menu">
                <button
                  ref={triggerRef}
                  type="button"
                  className="cortex-admin-user-menu__trigger"
                  aria-label={`${displayName}, account menu`}
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
                  <ChevronDown
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
                    aria-label="Account menu"
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
                          {me.role}
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
                      <UserCircle2 aria-hidden="true" size={16} />
                      <span>Account details</span>
                    </button>
                    <button
                      type="button"
                      role="menuitem"
                      className="cortex-admin-user-menu__item"
                      onClick={() => {
                        setMenuOpen(false);
                        window.open('https://docs.flow-cortex.local', '_blank', 'noopener');
                      }}
                    >
                      <HelpCircle aria-hidden="true" size={16} />
                      <span>Help &amp; docs</span>
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
                      <LogOut aria-hidden="true" size={16} />
                      <span>Sign out</span>
                    </button>
                  </div>
                )}
              </div>
            )}
          </div>
        </div>
      </header>
      <Sidebar role={me?.role} collapsed={collapsed} onToggle={() => setCollapsed((value) => !value)} />
      <main className="cortex-admin-content">
        <ErrorBoundary key={pathname}>
          <Outlet />
        </ErrorBoundary>
      </main>
      {me && (
        <dialog
          ref={accountDialogRef}
          className="cortex-admin-account-dialog"
          aria-labelledby="cortex-admin-account-dialog-title"
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
                  {me.role}
                </span>
              </div>
              <div className="cortex-admin-account-dialog__email-row">
                <Mail aria-hidden="true" size={13} />
                <span>{me.email ?? '—'}</span>
                <button
                  type="button"
                  className="cortex-admin-account-dialog__copy"
                  onClick={() => {
                    if (me.email) void navigator.clipboard.writeText(me.email);
                  }}
                  aria-label="Copy email to clipboard"
                  title="Copy email"
                >
                  <Copy aria-hidden="true" size={12} />
                </button>
              </div>
            </div>
            <button
              type="button"
              className="cortex-admin-account-dialog__close"
              onClick={() => accountDialogRef.current?.close()}
              aria-label="Close account dialog"
            >
              ✕
            </button>
          </header>

          <section className="cortex-admin-account-dialog__section" aria-labelledby="cortex-admin-account-dialog-actions">
            <h3 id="cortex-admin-account-dialog-actions">Account actions</h3>
            <div className="cortex-admin-account-dialog__actions">
              <button
                type="button"
                className="cortex-admin-account-dialog__action"
                onClick={() => accountDialogRef.current?.close()}
              >
                <UserCog aria-hidden="true" size={16} />
                <span>Edit profile</span>
                <span className="cortex-admin-account-dialog__action-meta">Name · initials</span>
              </button>
              <button
                type="button"
                className="cortex-admin-account-dialog__action"
                onClick={() => accountDialogRef.current?.close()}
              >
                <KeyRound aria-hidden="true" size={16} />
                <span>Change password</span>
                <span className="cortex-admin-account-dialog__action-meta">Last set 42 days ago</span>
              </button>
              <button
                type="button"
                className="cortex-admin-account-dialog__action"
                onClick={() => accountDialogRef.current?.close()}
              >
                <Users aria-hidden="true" size={16} />
                <span>Active sessions</span>
                <span className="cortex-admin-account-dialog__action-meta">2 devices · this browser</span>
              </button>
            </div>
          </section>

          <section className="cortex-admin-account-dialog__section" aria-labelledby="cortex-admin-account-dialog-security">
            <h3 id="cortex-admin-account-dialog-security">Security</h3>
            <div className="cortex-admin-account-dialog__security">
              <div className="cortex-admin-account-dialog__security-row">
                <div>
                  <strong>Two-factor authentication</strong>
                  <p>Require a TOTP code on every admin sign-in.</p>
                </div>
                <label className="cortex-admin-toggle" aria-label="Toggle two-factor authentication">
                  <input type="checkbox" defaultChecked={false} />
                  <span className="cortex-admin-toggle__track" aria-hidden="true">
                    <span className="cortex-admin-toggle__thumb" />
                  </span>
                </label>
              </div>
              <div className="cortex-admin-account-dialog__security-row">
                <div>
                  <strong>Session timeout</strong>
                  <p>Automatically sign out after inactivity.</p>
                </div>
                <span className="cortex-admin-account-dialog__chip">30 minutes</span>
              </div>
            </div>
          </section>

          <section className="cortex-admin-account-dialog__section cortex-admin-account-dialog__section--danger" aria-labelledby="cortex-admin-account-dialog-danger">
            <h3 id="cortex-admin-account-dialog-danger">Danger zone</h3>
            <button
              type="button"
              className="cortex-admin-account-dialog__danger"
              disabled
              title="Admin accounts cannot be deleted from this dialog. Use the identity provider instead."
            >
              <Trash2 aria-hidden="true" size={15} />
              <span>Delete account</span>
              <span className="cortex-admin-account-dialog__action-meta">Disabled for admin</span>
            </button>
          </section>
        </dialog>
      )}
    </div>
  );
}
