import { CaretDown, type Icon } from '@phosphor-icons/react';
import { useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink, useLocation } from 'react-router-dom';
import { DOCS_ICON, DOCS_SUB_ITEMS, primaryNavItems } from './navItems';
import { useFeatures } from './useFeatures';
import './Sidebar.css';

interface SidebarNavProps {
  /** The 72px rail: labels stay in the DOM and show as tooltips. */
  readonly rail: boolean;
  /** Called when an entry is chosen, so a drawer can close. */
  readonly onNavigate?: () => void;
}

interface EntryProps {
  readonly to: string;
  readonly label: string;
  readonly icon: Icon;
  readonly rail: boolean;
  readonly end?: boolean | undefined;
  readonly onNavigate?: (() => void) | undefined;
}

function Entry({
  to,
  label,
  icon: EntryIcon,
  rail,
  end = false,
  onNavigate,
}: Readonly<EntryProps>) {
  return (
    <NavLink
      to={to}
      end={end}
      onClick={onNavigate}
      className={({ isActive }) =>
        isActive ? 'sidebar-nav__link is-current' : 'sidebar-nav__link'
      }
    >
      {({ isActive }) => (
        <>
          <EntryIcon
            className="sidebar-nav__icon"
            size={20}
            weight={isActive ? 'fill' : 'regular'}
            aria-hidden="true"
          />
          <span className="sidebar-nav__label">{label}</span>
          {rail && (
            <span className="sidebar-nav__tip" aria-hidden="true">
              {label}
            </span>
          )}
        </>
      )}
    </NavLink>
  );
}

export function SidebarNav({ rail, onNavigate }: Readonly<SidebarNavProps>) {
  const { t } = useTranslation();
  const { pathname } = useLocation();
  const { chat } = useFeatures();
  const onDocs = pathname.startsWith('/docs');
  const [docsOpen, setDocsOpen] = useState(onDocs);
  const docsListId = useId();

  useEffect(() => {
    if (onDocs) setDocsOpen(true);
  }, [onDocs]);

  const DocsIcon = DOCS_ICON;
  const docsLabel = t('common.nav.docs');

  return (
    <nav className="sidebar-nav" aria-label={t('common.nav.label')}>
      <ul className="sidebar-nav__list">
        {primaryNavItems(t, { chat }).map((item) => (
          <li key={item.to}>
            <Entry
              to={item.to}
              label={item.label}
              icon={item.icon}
              end={item.end}
              rail={rail}
              onNavigate={onNavigate}
            />
          </li>
        ))}
        <li>
          {rail ? (
            <Entry to="/docs" label={docsLabel} icon={DOCS_ICON} rail onNavigate={onNavigate} />
          ) : (
            <>
              <button
                type="button"
                className={onDocs ? 'sidebar-nav__link is-current' : 'sidebar-nav__link'}
                aria-expanded={docsOpen}
                aria-controls={docsOpen ? docsListId : undefined}
                onClick={() => setDocsOpen((open) => !open)}
              >
                <DocsIcon
                  className="sidebar-nav__icon"
                  size={20}
                  weight={onDocs ? 'fill' : 'regular'}
                  aria-hidden="true"
                />
                <span className="sidebar-nav__label">{docsLabel}</span>
                <CaretDown
                  className={docsOpen ? 'sidebar-nav__caret is-open' : 'sidebar-nav__caret'}
                  size={16}
                  aria-hidden="true"
                />
              </button>
              {docsOpen && (
                <ul
                  id={docsListId}
                  className="sidebar-nav__sublist"
                  aria-label={t('common.nav.docsGroup')}
                >
                  {DOCS_SUB_ITEMS.map((item) => (
                    <li key={item.to}>
                      <NavLink
                        to={item.to}
                        end={item.to === '/docs'}
                        onClick={onNavigate}
                        className={({ isActive }) =>
                          isActive ? 'sidebar-nav__sublink is-current' : 'sidebar-nav__sublink'
                        }
                      >
                        {t(item.labelKey)}
                      </NavLink>
                    </li>
                  ))}
                </ul>
              )}
            </>
          )}
        </li>
      </ul>
    </nav>
  );
}
