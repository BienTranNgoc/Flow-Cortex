import { NodiMark } from '@flow-cortex/ui-kit';
import { SidebarSimple, X } from '@phosphor-icons/react';
import { useEffect, useRef, type SyntheticEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { NavLink } from 'react-router-dom';
import { NODI_WORDMARK } from './brand';
import { isVisible, NAV, type NavGroup, type NavItem, type NavLiveCount } from './nav';
import type { Caller } from './permissions';
import { useIsDesktop } from './useIsDesktop';

interface SidebarProps {
  readonly me: Caller | undefined;
  readonly collapsed: boolean;
  readonly onToggle: () => void;
  readonly drawerOpen?: boolean;
  readonly onDrawerClose?: () => void;
  readonly nav?: readonly NavGroup[];
}

const noop = () => {};

const LIVE_COUNT_NAME: Record<NavLiveCount, string> = {
  openSupportRequests: 'common.shell.nav.withOpenCount',
};

/** The rail list scrolls and so clips children; the tooltip is fixed and takes its y from its entry. */
function placeEntryTip(entry: HTMLElement) {
  const tip = entry.querySelector<HTMLElement>('.cortex-admin-nav__tip');
  if (!tip) return;
  const rect = entry.getBoundingClientRect();
  tip.style.top = `${rect.top + rect.height / 2}px`;
}

function placeTip(event: SyntheticEvent<HTMLElement>) {
  placeEntryTip(event.currentTarget);
}

/** Scrolling moves every entry under a fixed tooltip, so each one is re-placed and the open one follows its entry. */
function replaceTips(event: SyntheticEvent<HTMLElement>) {
  for (const entry of event.currentTarget.querySelectorAll<HTMLElement>('.cortex-admin-nav__item')) {
    placeEntryTip(entry);
  }
}

/**
 * Navigation drawn from `NAV`: entries whose key the caller holds link, disabled ones stay visible but inert.
 * From 1024px it is an `<aside>` that is the full width or the icon-only rail (`collapsed`);
 * below that it is a modal `<dialog>` drawer driven by `drawerOpen`.
 */
export function Sidebar({
  me,
  collapsed,
  onToggle,
  drawerOpen = false,
  onDrawerClose = noop,
  nav = NAV,
}: SidebarProps) {
  const { t } = useTranslation();
  const isDesktop = useIsDesktop();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const rail = isDesktop && collapsed;
  const collapseLabel = collapsed ? t('common.shell.nav.expand') : t('common.shell.nav.collapse');
  const nameOf = (item: NavItem, label: string, count: number) => {
    if (count <= 0) return label;
    const key = item.liveCount ? LIVE_COUNT_NAME[item.liveCount] : 'common.shell.nav.withCount';
    return t(key, { label, count });
  };

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (drawerOpen && !dialog.open) {
      dialog.showModal();
    } else if (!drawerOpen && dialog.open) {
      dialog.close();
    }
  }, [drawerOpen, isDesktop]);

  const closeDrawer = () => dialogRef.current?.close();

  const content = (
    <>
      <div className="cortex-admin-nav__top">
        <div className="cortex-admin-nav__brand">
          <NodiMark size={28} />
          <span className="cortex-admin-nav__brand-name">{NODI_WORDMARK}</span>
          <span className="cortex-admin-nav__brand-label">{t('common.shell.brandAdmin')}</span>
        </div>
        {isDesktop ? (
          <button
            type="button"
            className="cortex-admin-nav__collapse-toggle"
            aria-pressed={collapsed}
            aria-label={collapseLabel}
            onClick={onToggle}
          >
            <SidebarSimple aria-hidden="true" size={20} />
          </button>
        ) : (
          <button
            type="button"
            className="cortex-admin-nav__collapse-toggle"
            aria-label={t('common.shell.nav.closeMenu')}
            onClick={closeDrawer}
          >
            <X aria-hidden="true" size={20} />
          </button>
        )}
      </div>
      <nav
        aria-label={t('common.shell.nav.label')}
        className="cortex-admin-nav__groups"
        onScroll={replaceTips}
      >
        {nav.map((group) => {
          const items = group.items.filter((item) => isVisible(me, item));
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
                          tabIndex={0}
                          data-tag={tag}
                          onMouseEnter={placeTip}
                          onFocus={placeTip}
                        >
                          <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                            <Icon size={20} />
                          </span>
                          <span className="cortex-admin-nav__item-label">{label}</span>
                          <span className="cortex-admin-nav__item-tag">{tag}</span>
                          <span className="cortex-admin-nav__visually-hidden">
                            {t('common.shell.nav.unavailable')}
                          </span>
                          <span
                            className="cortex-admin-nav__tip"
                            aria-hidden="true"
                            data-tip={t('common.shell.nav.disabledTitle', { label, tag })}
                          />
                        </span>
                      </li>
                    );
                  }
                  const count = item.badge ?? 0;
                  const name = nameOf(item, label, count);
                  return (
                    <li key={item.path}>
                      <NavLink
                        to={item.path}
                        className={({ isActive }) =>
                          isActive ? 'cortex-admin-nav__item is-active' : 'cortex-admin-nav__item'
                        }
                        aria-label={count > 0 ? name : undefined}
                        onClick={closeDrawer}
                        onMouseEnter={placeTip}
                        onFocus={placeTip}
                      >
                        {({ isActive }) => (
                          <>
                            <span className="cortex-admin-nav__item-icon" aria-hidden="true">
                              <Icon size={20} weight={isActive ? 'fill' : 'regular'} />
                              {count > 0 && <span className="cortex-admin-nav__dot" />}
                            </span>
                            <span className="cortex-admin-nav__item-label">{label}</span>
                            {count > 0 && (
                              <span className="cortex-admin-nav__badge" aria-hidden="true">
                                {count}
                              </span>
                            )}
                            <span className="cortex-admin-nav__tip" aria-hidden="true" data-tip={name} />
                          </>
                        )}
                      </NavLink>
                    </li>
                  );
                })}
              </ul>
            </div>
          );
        })}
      </nav>
      <div className="cortex-admin-nav__footer">
        <span className="cortex-admin-nav__status" aria-hidden="true">
          <span className="cortex-admin-nav__status-dot" />
        </span>
        <span className="cortex-admin-nav__status-text">{t('common.shell.nav.systemStatus')}</span>
      </div>
    </>
  );

  if (isDesktop) {
    return (
      <aside className={rail ? 'cortex-admin-nav cortex-admin-nav--collapsed' : 'cortex-admin-nav'}>
        {content}
      </aside>
    );
  }
  return (
    <dialog
      ref={dialogRef}
      className="cortex-admin-nav cortex-admin-nav--drawer"
      aria-label={t('common.shell.nav.label')}
      onClose={onDrawerClose}
      onClick={(event) => {
        if (event.target === event.currentTarget) closeDrawer();
      }}
    >
      {content}
    </dialog>
  );
}
