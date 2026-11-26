import type { ActivityItemResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../app/EmptyState';
import { Timestamp } from '../../app/AdminList';
import { useUserActivity } from './useUserActivity';
import './userActivity.css';

/** The translated label under `users.activity.<group>`, or the raw value when the console has none. */
function knownLabel(t: TFunction, group: 'actions' | 'sources', value: string): string {
  const label = t(`users.activity.${group}.${value}`, { defaultValue: value, returnObjects: true });
  return typeof label === 'string' ? label : value;
}

function actorText(t: TFunction, item: ActivityItemResponse): string {
  if (item.actorType === 'user') return t('users.activity.actorMember');
  return item.actorLabel || '—';
}

function ActivityRow({ item }: Readonly<{ item: ActivityItemResponse }>) {
  const { t } = useTranslation();
  const source = item.source ? knownLabel(t, 'sources', item.source) : '—';
  const action = item.action ? knownLabel(t, 'actions', item.action) : '—';
  return (
    <tr>
      <td className="cortex-admin-activity__time">
        <Timestamp value={item.occurredAt} />
      </td>
      <td data-label={t('users.activity.columns.source')}>{source}</td>
      <td>
        <strong>{action}</strong>
      </td>
      <td data-label={t('users.activity.columns.actor')}>{actorText(t, item)}</td>
      <td data-label={t('users.activity.columns.object')}>{item.objectLabel || '—'}</td>
    </tr>
  );
}

/** The member's timeline on the user detail page: newest 50 items, Load more while a cursor remains. */
export function UserActivitySection({ userId }: Readonly<{ userId: string }>) {
  const { t } = useTranslation();
  const activity = useUserActivity(userId);
  const items = activity.data?.pages.flatMap((page) => page.items ?? []) ?? [];

  let body;
  if (activity.isPending) {
    body = <output className="cortex-admin-loading">{t('users.activity.loading')}</output>;
  } else if (activity.isError && items.length === 0) {
    body = (
      <div className="cortex-admin-error">
        <p role="alert">{t('users.activity.loadError')}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => activity.refetch()}>
          {t('users.activity.retry')}
        </button>
      </div>
    );
  } else if (items.length === 0) {
    body = (
      <EmptyState title={t('users.activity.empty')} description={t('users.activity.emptyHint')} />
    );
  } else {
    body = (
      <>
        <table className="cortex-admin-activity__table">
          <thead>
            <tr>
              <th scope="col">{t('users.activity.columns.time')}</th>
              <th scope="col">{t('users.activity.columns.source')}</th>
              <th scope="col">{t('users.activity.columns.action')}</th>
              <th scope="col">{t('users.activity.columns.actor')}</th>
              <th scope="col">{t('users.activity.columns.object')}</th>
            </tr>
          </thead>
          <tbody>
            {items.map((item) => (
              <ActivityRow key={item.id} item={item} />
            ))}
          </tbody>
        </table>
        {activity.isFetchNextPageError && (
          <p className="cortex-admin-form-error" role="alert">
            {t('users.activity.loadMoreError')}
          </p>
        )}
        {activity.hasNextPage && (
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-activity__more"
            onClick={() => activity.fetchNextPage()}
            disabled={activity.isFetchingNextPage}
          >
            {activity.isFetchingNextPage
              ? t('users.activity.loadingMore')
              : t('users.activity.loadMore')}
          </button>
        )}
      </>
    );
  }

  return (
    <section
      className="cortex-admin-panel cortex-admin-activity"
      aria-labelledby="user-activity-title"
    >
      <header className="cortex-admin-section-heading">
        <div>
          <h2 id="user-activity-title">{t('users.activity.title')}</h2>
          <p>{t('users.activity.hint')}</p>
        </div>
      </header>
      {body}
    </section>
  );
}
