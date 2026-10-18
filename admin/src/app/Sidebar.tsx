import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';
import { NAV } from './nav';
import { can } from './permissions';

/** Navigation drawn from `NAV`: permitted entries link, disabled ones stay visible but inert. */
export function Sidebar({ role }: Readonly<{ role: string | undefined }>) {
  const [open, setOpen] = useState(false);
  const { t } = useTranslation();

  return (
    <nav aria-label={t('common.shell.nav.label')} className="sidebar">
      <button
        type="button"
        className="sidebar-toggle"
        aria-expanded={open}
        aria-controls="sidebar-groups"
        onClick={() => setOpen((value) => !value)}
      >
        {t('common.shell.nav.menu')}
      </button>
      <div id="sidebar-groups" className={open ? 'sidebar-groups open' : 'sidebar-groups'}>
        {NAV.map((group) => {
          const items = group.items.filter((item) => can(role, item.action));
          if (items.length === 0) {
            return null;
          }
          const labelId = `nav-group-${group.id}`;
          return (
            <div key={group.id} className="sidebar-group">
              <p id={labelId} className="sidebar-group-label">
                {t(group.label)}
              </p>
              <ul aria-labelledby={labelId}>
                {items.map((item) => (
                  <li key={item.path}>
                    {item.disabled ? (
                      <span className="sidebar-item" aria-disabled="true">
                        <span aria-hidden="true">{item.icon}</span> <span>{t(item.label)}</span>{' '}
                        <small>{t('common.shell.nav.comingSoon')}</small>
                        <span className="visually-hidden"> {t('common.shell.nav.unavailable')}</span>
                      </span>
                    ) : (
                      <NavLink to={item.path} className="sidebar-item" onClick={() => setOpen(false)}>
                        <span aria-hidden="true">{item.icon}</span> {t(item.label)}
                      </NavLink>
                    )}
                  </li>
                ))}
              </ul>
            </div>
          );
        })}
      </div>
    </nav>
  );
}
