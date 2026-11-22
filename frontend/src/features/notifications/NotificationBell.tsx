import { Bell } from '@phosphor-icons/react';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { NotificationRow } from './NotificationRow';
import { useChooseNotification } from './useChooseNotification';
import { useNotificationStream } from './useNotificationStream';
import { entriesOf, useLatest, useMarkAllRead, useUnreadCount } from './useNotifications';
import './NotificationBell.css';

const BADGE_CAP = 99;

/** Topbar bell: unread badge and a disclosure panel with the latest notifications. */
export function NotificationBell() {
  const { t } = useTranslation();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelId = useId();
  const titleId = useId();
  const unread = useUnreadCount();
  const latest = useLatest(open);
  const markAll = useMarkAllRead();
  const choose = useChooseNotification(() => setOpen(false));
  useNotificationStream();

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && rootRef.current?.contains(event.target)) return;
      setOpen(false);
    };
    const onKey = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKey);
    };
  }, [open]);

  const entries = entriesOf(latest.data);
  const badge = unread > BADGE_CAP ? `${BADGE_CAP}+` : String(unread);

  return (
    <div
      ref={rootRef}
      className="notification-bell"
      onBlur={(event) => {
        const next = event.relatedTarget;
        if (open && next instanceof Node && !rootRef.current?.contains(next)) setOpen(false);
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="notification-bell__trigger"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t('notifications.bell.label', { unread })}
        onClick={() => setOpen((prev) => !prev)}
      >
        <Bell size={20} aria-hidden="true" />
        {unread > 0 && (
          <span className="notification-bell__badge" aria-hidden="true">
            {badge}
          </span>
        )}
      </button>

      {open && (
        <section id={panelId} className="notification-bell__panel" aria-labelledby={titleId}>
          <header className="notification-bell__head">
            <h2 id={titleId} className="notification-bell__title">
              {t('notifications.title')}
            </h2>
            {unread > 0 && (
              <button
                type="button"
                className="notification-bell__action"
                disabled={markAll.isPending}
                onClick={() => markAll.mutate()}
              >
                {t('notifications.markAllRead')}
              </button>
            )}
          </header>

          <div className="notification-bell__body" aria-busy={latest.isLoading}>
            {latest.isLoading && <Skeleton shape="row" count={3} />}
            {latest.isError && (
              <StatusBlock
                kind="error"
                message={t('notifications.loadError')}
                action={{ label: t('notifications.retry'), onClick: () => void latest.refetch() }}
              />
            )}
            {latest.isSuccess && entries.length === 0 && (
              <p className="notification-bell__empty">{t('notifications.empty')}</p>
            )}
            {latest.isSuccess &&
              entries.map((entry) => <NotificationRow key={entry.id} entry={entry} onChoose={choose} />)}
          </div>

          <footer className="notification-bell__foot">
            <Link to="/notifications" className="notification-bell__all" onClick={() => setOpen(false)}>
              {t('notifications.viewAll')}
            </Link>
          </footer>
        </section>
      )}
    </div>
  );
}
