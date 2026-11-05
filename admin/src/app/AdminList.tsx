import { ApiError } from '@flow-cortex/api-client';
import type { UseQueryResult } from '@tanstack/react-query';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { formatDateTime, formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';
import { TableRegion } from './TableRegion';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

interface AdminListProps<T> {
  query: UseQueryResult<Page<T>>;
  /** Key of a lower-case noun with `_one`/`_other` forms, resolved with the row count: "run" / "runs". */
  nounKey: string;
  /** Key of the translated accessible name for the table region. */
  tableLabelKey: string;
  statuses: readonly string[];
  /** The filter option's text for a status code; the option value stays the code. */
  statusLabel: (status: string) => string;
  status: string;
  /** A filter the list cannot see (not the status one) is active: an empty result reads as filtered. */
  filtered?: boolean;
  onStatusChange: (status: string) => void;
  onPageChange: (page: number) => void;
  children: (items: T[]) => ReactNode;
}

/**
 * Toolbar, states and pager shared by the operational lists. A failed refetch keeps the rows
 * already shown and says so; a 503 is the console's kill switch, shown as a banner, not an error.
 */
export function AdminList<T>({
  query,
  nounKey,
  tableLabelKey,
  statuses,
  statusLabel,
  status,
  filtered = false,
  onStatusChange,
  onPageChange,
  children,
}: Readonly<AdminListProps<T>>) {
  const { t } = useTranslation();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;
  const filterId = useId();
  const noun = t(nounKey, { count: 0 });

  let empty: ReactNode;
  if (data && data.page > 0) {
    empty = (
      <>
        <p>{t('common.shell.list.emptyPage', { noun })}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => onPageChange(0)}>
          {t('common.shell.list.backToFirstPage')}
        </button>
      </>
    );
  } else if (status) {
    empty = (
      <>
        <p>{t('common.shell.list.noMatch', { noun })}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => onStatusChange('')}>
          {t('common.shell.list.clearFilter')}
        </button>
      </>
    );
  } else if (filtered) {
    empty = <p>{t('common.shell.list.noMatch', { noun })}</p>;
  } else {
    empty = <p>{t('common.shell.list.empty', { noun })}</p>;
  }

  return (
    <>
      <div className="cortex-admin-toolbar">
        <div className="cortex-admin-field">
          <label htmlFor={filterId}>{t('common.shell.list.status')}</label>
          <select id={filterId} value={status} onChange={(event) => onStatusChange(event.target.value)}>
            <option value="">{t('common.shell.list.allStatuses')}</option>
            {statuses.map((value) => (
              <option key={value} value={value}>
                {statusLabel(value)}
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
          {t('common.shell.list.refresh')}
        </button>
      </div>

      {disabled && (
        <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
      )}
      {error && !disabled && (
        <div className="cortex-admin-error">
          <p role="alert">{t('common.shell.list.loadError', { noun })}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {(query.isPending || query.isPlaceholderData) && (
        <output className="cortex-admin-loading">{t('common.shell.list.loading', { noun })}</output>
      )}
      {data && error && <p className="cortex-admin-stale">{t('common.shell.list.stale')}</p>}
      {data?.items.length === 0 && <div className="cortex-admin-empty">{empty}</div>}
      {data && data.items.length > 0 && (
        <TableRegion label={t(tableLabelKey)} busy={query.isPlaceholderData}>
          {children(data.items)}
        </TableRegion>
      )}
      {data && (data.items.length > 0 || data.page > 0) && (
        <Pager page={data.page} size={data.size} total={data.total} nounKey={nounKey} onPageChange={onPageChange} />
      )}
    </>
  );
}

function Pager({
  page,
  size,
  total,
  nounKey,
  onPageChange,
}: Readonly<{ page: number; size: number; total: number; nounKey: string; onPageChange: (page: number) => void }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  // A page past the end (rows removed since the last page was read) still counts as a page.
  const pages = Math.max(1, Math.ceil(total / size), page + 1);
  const hasPrevious = page > 0;
  const hasNext = page + 1 < pages;

  // aria-disabled rather than disabled, so focus stays on the button when the last page is reached.
  return (
    <div className="cortex-admin-pager">
      <button
        type="button"
        className="cortex-admin-btn"
        aria-disabled={!hasPrevious}
        onClick={() => hasPrevious && onPageChange(page - 1)}
      >
        {t('common.shell.list.previousPage')}
      </button>
      <span>
        {t('common.shell.list.pager', {
          page: formatNumber(page + 1, locale),
          pages: formatNumber(pages, locale),
          total: formatNumber(total, locale),
          noun: t(nounKey, { count: total }),
        })}
      </span>
      <button
        type="button"
        className="cortex-admin-btn"
        aria-disabled={!hasNext}
        onClick={() => hasNext && onPageChange(page + 1)}
      >
        {t('common.shell.list.nextPage')}
      </button>
    </div>
  );
}

// Same fields toLocaleString() prints, so en-US output is unchanged.
const TIMESTAMP_FORMAT: Intl.DateTimeFormatOptions = {
  year: 'numeric',
  month: 'numeric',
  day: 'numeric',
  hour: 'numeric',
  minute: 'numeric',
  second: 'numeric',
};

/** A timestamp cell's content: the local time in the active language, or a dash when the row has none. */
export function Timestamp({ value }: Readonly<{ value: string | undefined }>) {
  // Subscribes the cell to language switches; activeLocale() alone would not re-render it.
  useTranslation();
  return value ? (
    <time dateTime={value}>{formatDateTime(value, activeLocale(), TIMESTAMP_FORMAT)}</time>
  ) : (
    <>—</>
  );
}
