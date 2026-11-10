import { useId, useRef, useState, type KeyboardEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import { NotificationRow } from './NotificationRow';
import { useChooseNotification } from './useChooseNotification';
import {
  entriesOf,
  useMarkAllRead,
  useNotificationList,
  useUnreadCount,
  type NotificationFilter,
} from './useNotifications';
import './NotificationsPage.css';

const FILTERS: readonly NotificationFilter[] = ['all', 'unread'];

/** The member's whole inbox: All and Unread tabs, 20 at a time. */
export function NotificationsPage() {
  const { t } = useTranslation();
  const [filter, setFilter] = useState<NotificationFilter>('all');
  const tabRefs = useRef<Record<NotificationFilter, HTMLButtonElement | null>>({ all: null, unread: null });
  const idBase = useId();
  const unread = useUnreadCount();
  const list = useNotificationList(filter);
  const markAll = useMarkAllRead();
  const choose = useChooseNotification();

  const entries = (list.data?.pages ?? []).flatMap(entriesOf);
  const tabId = (value: NotificationFilter) => `${idBase}-tab-${value}`;
  const panelId = `${idBase}-panel`;

  const onTabKey = (event: KeyboardEvent<HTMLButtonElement>) => {
    const index = FILTERS.indexOf(filter);
    let next: number;
    if (event.key === 'ArrowRight') next = (index + 1) % FILTERS.length;
    else if (event.key === 'ArrowLeft') next = (index - 1 + FILTERS.length) % FILTERS.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = FILTERS.length - 1;
    else return;
    event.preventDefault();
    const target = FILTERS[next] ?? 'all';
    setFilter(target);
    tabRefs.current[target]?.focus();
  };

  return (
    <div className="notifications-page">
      <PageHeading
        title={t('notifications.title')}
        actions={
          <>
            {unread > 0 && (
              <button type="button" disabled={markAll.isPending} onClick={() => markAll.mutate()}>
                {t('notifications.markAllRead')}
              </button>
            )}
            <Link to="/notifications/preferences" className="action-link">
              {t('notifications.preferencesLink')}
            </Link>
          </>
        }
      />

      <div role="tablist" aria-label={t('notifications.filterLabel')} className="notifications-page__tabs">
        {FILTERS.map((value) => (
          <button
            key={value}
            ref={(node) => {
              tabRefs.current[value] = node;
            }}
            id={tabId(value)}
            type="button"
            role="tab"
            aria-selected={filter === value}
            aria-controls={panelId}
            tabIndex={filter === value ? 0 : -1}
            className="notifications-page__tab"
            onClick={() => setFilter(value)}
            onKeyDown={onTabKey}
          >
            {t(`notifications.filter.${value}`)}
          </button>
        ))}
      </div>

      <section
        id={panelId}
        role="tabpanel"
        aria-labelledby={tabId(filter)}
        aria-busy={list.isLoading}
        className="notifications-page__list"
      >
        {list.isLoading && <Skeleton shape="row" count={4} />}
        {list.isError && (
          <StatusBlock
            kind="error"
            message={t('notifications.loadError')}
            action={{ label: t('notifications.retry'), onClick: () => void list.refetch() }}
          />
        )}
        {list.isSuccess && entries.length === 0 && (
          <StatusBlock
            kind="empty"
            message={t(filter === 'unread' ? 'notifications.emptyUnread' : 'notifications.empty')}
            action={{ label: t('notifications.goToWorkflows'), to: '/workflows' }}
          />
        )}
        {entries.map((entry) => (
          <NotificationRow key={entry.id} entry={entry} onChoose={choose} />
        ))}
        {list.isSuccess && list.hasNextPage && (
          <button
            type="button"
            className="notifications-page__more"
            disabled={list.isFetchingNextPage}
            onClick={() => void list.fetchNextPage()}
          >
            {t('notifications.loadMore')}
          </button>
        )}
        {list.isFetchNextPageError && (
          <p role="alert" className="form-error">
            {t('notifications.loadError')}
          </p>
        )}
      </section>
    </div>
  );
}
