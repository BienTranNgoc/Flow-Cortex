import { useEffect, useRef, useState } from 'react';
import { Link, useNavigate } from 'react-router-dom';
import { setToken, signOut } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { accountStatsEnabled } from './featureFlags';
import './UserMenu.css';

interface UserMenuProps {
  readonly name: string | string[] | null | undefined;
  readonly email: string | null | undefined;
}

function initialsFrom(name: string | string[] | null | undefined, email: string | null | undefined): string {
  const source = (typeof name === 'string' ? name : '').trim() || (email ?? '');
  const parts = source.split(/[\s@.]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

/** Round-avatar trigger in the top-right header that opens account / quota / sign-out. */
export function UserMenu({ name, email }: Readonly<UserMenuProps>) {
  const [open, setOpen] = useState(false);
  const [signingOut, setSigningOut] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
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
      if (event.key === 'Escape') setOpen(false);
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
        type="button"
        className="cortex-user-menu__trigger"
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={email ? `Open account menu for ${email}` : 'Open account menu'}
        onClick={() => setOpen((prev) => !prev)}
      >
        <span className="cortex-user-menu__avatar" aria-hidden="true">
          {initials}
        </span>
      </button>

      {open && (
        <div className="cortex-user-menu__panel" role="menu">
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
            <div className="cortex-user-menu__quota" aria-label="Resource quotas">
              <div className="cortex-user-menu__quota-row">
                <span>GPU minutes</span>
                <span className="cortex-user-menu__quota-value">120 / 240</span>
              </div>
              <div className="cortex-user-menu__quota-row">
                <span>Token balance</span>
                <span className="cortex-user-menu__quota-value">84,500</span>
              </div>
            </div>
          )}

          <Link
            to="/profile"
            className="cortex-user-menu__item"
            role="menuitem"
            onClick={() => setOpen(false)}
          >
            <span>Account settings</span>
          </Link>
          <Link
            to="/integrations"
            className="cortex-user-menu__item"
            role="menuitem"
            onClick={() => setOpen(false)}
          >
            <span>Connected services</span>
          </Link>
          <Link
            to="/docs"
            className="cortex-user-menu__item"
            role="menuitem"
            onClick={() => setOpen(false)}
          >
            <span>Help &amp; docs</span>
          </Link>

          <div className="cortex-user-menu__divider" aria-hidden="true" />

          <button
            type="button"
            className="cortex-user-menu__item cortex-user-menu__item--danger"
            role="menuitem"
            onClick={onSignOut}
            disabled={signingOut}
          >
            {signingOut ? 'Signing out…' : 'Sign out'}
          </button>
        </div>
      )}
    </div>
  );
}
