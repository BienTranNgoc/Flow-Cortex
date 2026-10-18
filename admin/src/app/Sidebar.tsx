import { useState } from 'react';
import { NavLink } from 'react-router-dom';
import { Menu, PanelRight, PanelLeft, X } from 'lucide-react';
import { NAV } from './nav';
import { can } from './permissions';

/**
 * Sidebar nav drawn from `NAV`. Each permitted entry links; each disabled entry
 * shows as inert text with a "Coming soon" tag. The shell passes a `collapsed`
 * flag (icon-only, 64px) controlled by the toggle so the user can pin the
 * sidebar as wide or as a rail.
 *
 * Structure (vertical): Header (brand + collapse toggle) → scrollable Content
 * (grouped menu items) → Footer (system status). The collapse toggle floats
 * on the right border so it never overlaps the first item.
 */
export function Sidebar({
  role,
  collapsed,
  onToggle,
}: Readonly<{ role: string | undefined; collapsed: boolean; onToggle: () => void }>) {
  const [openMobile, setOpenMobile] = useState(false);
  const widthClass = collapsed ? ' cortex-admin-nav--collapsed' : '';

  return (
    <nav
      aria-label="Admin navigation"
      className={`cortex-admin-nav${widthClass}`}
    >
      <div className="cortex-admin-nav__header" aria-hidden="true" />
      <button
        type="button"
        className="cortex-admin-nav__collapse-toggle"
        aria-pressed={collapsed}
        aria-label={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        title={collapsed ? 'Expand sidebar' : 'Collapse sidebar'}
        onClick={onToggle}
      >
        {collapsed ? (
          <PanelLeft aria-hidden="true" size={14} />
        ) : (
          <PanelRight aria-hidden="true" size={14} />
        )}
      </button>
      <button
        type="button"
        className="cortex-admin-nav__toggle"
        aria-expanded={openMobile}
        aria-controls="cortex-admin-nav-groups"
        onClick={() => setOpenMobile((value) => !value)}
      >
        {openMobile ? <X aria-hidden="true" size={16} /> : <Menu aria-hidden="true" size={16} />}
        <span>{openMobile ? 'Hide menu' : 'Menu'}</span>
      </button>
      <div
        id="cortex-admin-nav-groups"
        className={
          openMobile
            ? 'cortex-admin-nav__groups is-open'
            : 'cortex-admin-nav__groups'
        }
      >
        {NAV.map((group) => {
          const items = group.items.filter((item) => can(role, item.action));
          if (items.length === 0) {
            return null;
          }
          const labelId = `cortex-admin-nav-group-${group.label
            .toLowerCase()
            .replace(/\s+/g, '-')}`;
          return (
            <div key={group.label} className="cortex-admin-nav__group">
              <p id={labelId} className="cortex-admin-nav__group-label">
                {group.label}
              </p>
              <ul className="cortex-admin-nav__list" aria-labelledby={labelId}>
                {items.map((item) => {
                  const Icon = item.icon;
                  if (item.disabled) {
                    return (
                      <li key={item.path}>
                        <span
                          className="cortex-admin-nav__item"
                          aria-disabled="true"
                          data-tag={item.tag ?? 'Coming soon'}
                          title={`${item.label} — ${item.tag ?? 'Coming soon'}`}
                        >
                          <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                            <Icon size={18} />
                          </span>
                          <span className="cortex-admin-nav__item-label">{item.label}</span>
                          <span className="cortex-admin-nav__item-tag">
                            {item.tag ?? 'Coming soon'}
                          </span>
                          <span className="cortex-admin-nav__visually-hidden">unavailable</span>
                        </span>
                      </li>
                    );
                  }
                  return (
                    <li key={item.path}>
                      <NavLink
                        to={item.path}
                        className={({ isActive }) =>
                          isActive
                            ? 'cortex-admin-nav__item is-active'
                            : 'cortex-admin-nav__item'
                        }
                        onClick={() => setOpenMobile(false)}
                        title={item.label}
                      >
                        <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                          <Icon size={18} />
                        </span>
                        <span className="cortex-admin-nav__item-label">{item.label}</span>
                      </NavLink>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </div>
      <div className="cortex-admin-nav__footer">
        <span className="cortex-admin-nav__status" aria-hidden="true">
          <span className="cortex-admin-nav__status-dot" />
        </span>
        {!collapsed && (
          <span className="cortex-admin-nav__status-text">All systems normal</span>
        )}
      </div>
    </nav>
  );
}
