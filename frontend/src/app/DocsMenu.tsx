import { useEffect, useRef, useState } from 'react';
import { NavLink } from 'react-router-dom';
import { DOCS_SUB_ITEMS } from './navItems';
import './DocsMenu.css';

interface DocsMenuProps {
  readonly pathname: string;
}

/** A "Docs" nav link that opens a small popover with Overview / API Reference / Guides. */
export function DocsMenu({ pathname }: Readonly<DocsMenuProps>) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const isActive = pathname.startsWith('/docs');  useEffect(() => {
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

  // Close when the route changes — the user is navigating away.
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  return (
    <div ref={rootRef} className="cortex-docs-menu">
      <button
        type="button"
        className={`cortex-header__nav-link cortex-docs-menu__trigger${isActive ? ' is-active' : ''}`}
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((prev) => !prev)}
      >
        Docs
        <svg
          className={`cortex-docs-menu__chevron${open ? ' is-open' : ''}`}
          viewBox="0 0 24 24"
          width="12"
          height="12"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
          aria-hidden="true"
        >
          <path d="M6 9l6 6 6-6" />
        </svg>
      </button>
      {open && (
        <div className="cortex-docs-menu__panel" role="menu">
          {DOCS_SUB_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/docs'}
              className={({ isActive: active }) =>
                `cortex-docs-menu__item${active ? ' is-active' : ''}`
              }
              role="menuitem"
              onClick={() => setOpen(false)}
            >
              {item.label}
            </NavLink>
          ))}
          <NavLink
            to="/docs/api"
            end={false}
            className="cortex-docs-menu__hint"
            onClick={() => setOpen(false)}
          >
            See full API Reference →
          </NavLink>
        </div>
      )}
    </div>
  );
}
