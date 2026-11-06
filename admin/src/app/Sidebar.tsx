import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';
import { Menu, PanelRight, PanelLeft, X } from 'lucide-react';
import { NAV } from './nav';
import { can } from './permissions';

/**
 * Navigation drawn from `NAV`: permitted entries link, disabled ones stay visible but inert.
 * `collapsed` switches between the full width and the icon-only rail.
 */
export function Sidebar({
  role,
  collapsed,
  onToggle,
}: Readonly<{ role: string | undefined; collapsed: boolean; onToggle: () => void }>) {
  const [openMobile, setOpenMobile] = useState(false);
  const { t } = useTranslation();
  const widthClass = collapsed ? ' cortex-admin-nav--collapsed' : '';
  const collapseLabel = collapsed ? t('common.shell.nav.expand') : t('common.shell.nav.collapse');

  return (
    <nav
      aria-label={t('common.shell.nav.label')}
      className={`cortex-admin-nav${widthClass}`}
    >
      <div className="cortex-admin-nav__header" aria-hidden="true" />
      <button
        type="button"
        className="cortex-admin-nav__collapse-toggle"
        aria-pressed={collapsed}
        aria-label={collapseLabel}
        title={collapseLabel}
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
        <span>{openMobile ? t('common.shell.nav.hideMenu') : t('common.shell.nav.menu')}</span>
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
          const labelId = `cortex-admin-nav-group-${group.id}`;
          return (
            <div key={group.id} className="cortex-admin-nav__group">
              <p id={labelId} className="cortex-admin-nav__group-label">
                {t(group.label)}
              </p>
              <ul className="cortex-admin-nav__list" aria-labelledby={labelId}>
                {items.map((item) => {
                  const Icon = item.icon;
                  const label = t(item.label);
                  if (item.disabled) {
                    const tag = t(item.tag ?? 'common.shell.nav.comingSoon');
                    return (
                      <li key={item.path}>
                        <span
                          className="cortex-admin-nav__item"
                          aria-disabled="true"
                          data-tag={tag}
                          title={t('common.shell.nav.disabledTitle', { label, tag })}
                        >
                          <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                            <Icon size={18} />
                          </span>
                          <span className="cortex-admin-nav__item-label">{label}</span>
                          <span className="cortex-admin-nav__item-tag">{tag}</span>
                          <span className="cortex-admin-nav__visually-hidden">
                            {t('common.shell.nav.unavailable')}
                          </span>
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
                        title={label}
                      >
                        <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                          <Icon size={18} />
                        </span>
                        <span className="cortex-admin-nav__item-label">{label}</span>
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
          <span className="cortex-admin-nav__status-text">{t('common.shell.nav.systemStatus')}</span>
        )}
      </div>
    </nav>
  );
}
