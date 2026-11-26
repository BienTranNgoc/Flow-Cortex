import { Bell } from '@phosphor-icons/react';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useNavigate } from 'react-router-dom';
import { RequestTime } from '../features/support/requests/RequestTime';
import './adminBell.css';
import {
  ADMIN_NOTIFICATIONS_KEY,
  isInAppLink,
  notificationsOf,
  useAdminNotificationList,
  useAdminUnreadCount,
  useMarkAdminRead,
  useMarkAllAdminRead,
  type AdminNotification,
} from './useAdminNotifications';

const BADGE_CAP = 99;

/** Topbar bell for the admin audience: unread badge and a panel with the latest notifications. */
export function AdminBell() {
  const { t } = useTranslation();
  const queryClient = useQueryClient();
  const navigate = useNavigate();
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement | null>(null);
  const triggerRef = useRef<HTMLButtonElement | null>(null);
  const panelId = useId();
  const titleId = useId();
  const unread = useAdminUnreadCount();
  const list = useAdminNotificationList(open);
  const markRead = useMarkAdminRead();
  const markAll = useMarkAllAdminRead();

  useEffect(() => {
    if (!open) return undefined;
    const onPointerDown = (event: PointerEvent) => {
      if (event.target instanceof Node && rootRef.current?.contains(event.target)) return;
      setOpen(false);
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key !== 'Escape') return;
      setOpen(false);
      triggerRef.current?.focus();
    };
    document.addEventListener('pointerdown', onPointerDown);
    document.addEventListener('keydown', onKeyDown);
    return () => {
      document.removeEventListener('pointerdown', onPointerDown);
      document.removeEventListener('keydown', onKeyDown);
    };
  }, [open]);

  const toggle = () => {
    if (!open) void queryClient.invalidateQueries({ queryKey: ADMIN_NOTIFICATIONS_KEY });
    setOpen((value) => !value);
  };

  const choose = (entry: AdminNotification) => {
    if (!entry.read) markRead.mutate(entry.id);
    setOpen(false);
    if (isInAppLink(entry.link)) navigate(entry.link);
  };

  const entries = notificationsOf(list.data);
  const badge = unread > BADGE_CAP ? `${BADGE_CAP}+` : String(unread);

  return (
    <div
      ref={rootRef}
      className="admin-bell"
      onBlur={(event) => {
        const next = event.relatedTarget;
        if (open && next instanceof Node && !rootRef.current?.contains(next)) setOpen(false);
      }}
    >
      <button
        ref={triggerRef}
        type="button"
        className="admin-bell__trigger"
        aria-expanded={open}
        aria-controls={open ? panelId : undefined}
        aria-label={t('common.bell.label', { unread })}
        onClick={toggle}
      >
        <Bell aria-hidden="true" size={20} />
        {unread > 0 && (
          <span className="admin-bell__badge" aria-hidden="true">
            {badge}
          </span>
        )}
      </button>

      {open && (
        <section id={panelId} className="admin-bell__panel" aria-labelledby={titleId}>
          <header className="admin-bell__head">
            <h2 id={titleId} className="admin-bell__title">
              {t('common.bell.title')}
            </h2>
            {unread > 0 && (
              <button
                type="button"
                className="admin-bell__action"
                disabled={markAll.isPending}
                onClick={() => markAll.mutate()}
              >
                {t('common.bell.markAllRead')}
              </button>
            )}
          </header>

          <div className="admin-bell__body" aria-busy={list.isPending}>
            {markAll.isError && (
              <p className="admin-bell__note" role="alert">
                {t('common.bell.markAllFailed')}
              </p>
            )}
            {list.isPending && (
              <output className="admin-bell__note">{t('common.bell.loading')}</output>
            )}
            {list.isError && (
              <div className="admin-bell__error" role="alert">
                <p>{t('common.bell.loadError')}</p>
                <button
                  type="button"
                  className="cortex-admin-btn"
                  onClick={() => void list.refetch()}
                >
                  {t('common.bell.retry')}
                </button>
              </div>
            )}
            {list.isSuccess && entries.length === 0 && (
              <p className="admin-bell__note">{t('common.bell.empty')}</p>
            )}
            {list.isSuccess &&
              entries.map((entry) => (
                <button
                  key={entry.id}
                  type="button"
                  className="admin-bell__item"
                  data-unread={entry.read ? undefined : 'true'}
                  onClick={() => choose(entry)}
                >
                  <span className="admin-bell__mark" aria-hidden="true" />
                  <span className="admin-bell__text">
                    <span className="admin-bell__item-title">
                      {!entry.read && (
                        <span className="visually-hidden">{t('common.bell.unreadMark')} </span>
                      )}
                      {entry.title}
                    </span>
                    {entry.body && <span className="admin-bell__item-body">{entry.body}</span>}
                    {entry.createdAt && (
                      <span className="admin-bell__item-time">
                        <RequestTime value={entry.createdAt} />
                      </span>
                    )}
                  </span>
                </button>
              ))}
          </div>
        </section>
      )}
    </div>
  );
}
