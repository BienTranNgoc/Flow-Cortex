import { ErrorBoundary } from '@flow-cortex/ui-kit';
import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { Outlet, useLocation } from 'react-router-dom';
import { logout, useMeQuery } from '../features/auth/useAuth';
import { Sidebar } from './Sidebar';

/** Console shell. The page area is keyed by path so navigating away from a failed page clears it. */
export function AdminLayout() {
  const me = useMeQuery().data;
  const { pathname } = useLocation();
  const [menuOpen, setMenuOpen] = useState(false);
  const [accountOpen, setAccountOpen] = useState(false);
  const triggerRef = useRef<HTMLButtonElement>(null);
  const menuRef = useRef<HTMLDivElement>(null);
  const accountDialogRef = useRef<HTMLDialogElement>(null);
  const displayName = me?.name?.trim() || me?.email || 'Account';
  const avatarInitial = displayName.trim().charAt(0).toLocaleUpperCase();

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
    if (accountOpen && !accountDialogRef.current?.open) {
      accountDialogRef.current?.showModal();
    }
  }, [accountOpen]);

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
      menuRef.current?.querySelectorAll<HTMLButtonElement>('[role="menuitem"]:not(:disabled)') ??
        [],
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

  const openAccount = () => {
    setMenuOpen(false);
    setAccountOpen(true);
  };

  return (
    <div className="admin-shell">
      <header className="admin-header">
        <span className="admin-title">Flow-Cortex Admin</span>
        {me && (
          <div className="admin-user">
            <div className="admin-account-menu">
              <button
                ref={triggerRef}
                type="button"
                className="admin-account-trigger"
                aria-label={`${displayName}, account menu`}
                aria-haspopup="menu"
                aria-expanded={menuOpen}
                aria-controls="admin-account-dropdown"
                onClick={() => setMenuOpen((open) => !open)}
              >
                <span className="user-avatar admin-account-avatar" aria-hidden="true">
                  {avatarInitial}
                </span>
                <span className="admin-account-name">{displayName}</span>
                <span className="admin-account-chevron" aria-hidden="true">
                  ▾
                </span>
              </button>
              {menuOpen && (
                <div
                  ref={menuRef}
                  id="admin-account-dropdown"
                  className="admin-account-dropdown"
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
                  <fieldset className="admin-account-summary" aria-label="Signed-in account">
                    <strong>{displayName}</strong>
                    <span>{me.email}</span>
                    <span className="status-badge">{me.role}</span>
                  </fieldset>
                  <div className="admin-account-menu-items">
                    <button type="button" role="menuitem" onClick={openAccount}>
                      Account
                    </button>
                    <button type="button" role="menuitem" disabled aria-disabled="true">
                      <span>Settings</span>
                      <small>Coming soon</small>
                    </button>
                    <button type="button" role="menuitem" disabled aria-disabled="true">
                      <span>Language</span>
                      <small>Coming soon</small>
                    </button>
                    <hr className="admin-account-divider" />
                    <button
                      type="button"
                      role="menuitem"
                      className="admin-account-signout"
                      onClick={() => {
                        setMenuOpen(false);
                        logout();
                      }}
                    >
                      Sign out
                    </button>
                  </div>
                </div>
              )}
            </div>
            <dialog
              ref={accountDialogRef}
              className="admin-account-dialog"
              aria-labelledby="admin-account-dialog-title"
              onClose={() => {
                setAccountOpen(false);
                triggerRef.current?.focus();
              }}
            >
              <div className="admin-account-dialog-heading">
                <h2 id="admin-account-dialog-title">Account</h2>
                <button
                  type="button"
                  aria-label="Close account details"
                  onClick={() => accountDialogRef.current?.close()}
                >
                  ×
                </button>
              </div>
              <dl className="admin-account-details">
                <div>
                  <dt>Name</dt>
                  <dd>{me.name || '—'}</dd>
                </div>
                <div>
                  <dt>Email</dt>
                  <dd>{me.email}</dd>
                </div>
                <div>
                  <dt>Role</dt>
                  <dd>{me.role}</dd>
                </div>
              </dl>
            </dialog>
          </div>
        )}
      </header>
      <Sidebar role={me?.role} />
      <div className="admin-content">
        <ErrorBoundary key={pathname}>
          <Outlet />
        </ErrorBoundary>
      </div>
    </div>
  );
}
