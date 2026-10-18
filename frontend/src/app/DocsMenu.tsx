import { useEffect, useId, useRef, useState } from 'react';
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
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelId = useId();
  const isActive = pathname.startsWith('/docs');

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

  // Close when the route changes — the user is navigating away.
  useEffect(() => {
    setOpen(false);
  }, [pathname]);

  return (
    <div ref={rootRef} className="cortex-docs-menu">
      <button
        ref={triggerRef}
        type="button"
        className={`cortex-header__nav-link cortex-docs-menu__trigger${isActive ? ' is-active' : ''}`}
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
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
        <div id={panelId} className="cortex-docs-menu__panel">
          {DOCS_SUB_ITEMS.map((item) => (
            <NavLink
              key={item.to}
              to={item.to}
              end={item.to === '/docs'}
              className={({ isActive: active }) =>
                `cortex-docs-menu__item${active ? ' is-active' : ''}`
              }
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
