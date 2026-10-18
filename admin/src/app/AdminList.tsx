import { ApiError } from '@flow-cortex/api-client';
import type { UseQueryResult } from '@tanstack/react-query';
import { useId, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { formatDateTime, formatNumber } from '../lib/format';
import { activeLocale } from '../lib/i18n';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

interface AdminListProps<T> {
  query: UseQueryResult<Page<T>>;
  /** Already translated, plural, lower case: "runs", "media jobs". */
  noun: string;
  statuses: readonly string[];
  /** The filter option's text for a status code; the option value stays the code. */
  statusLabel: (status: string) => string;
  status: string;
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
  noun,
  statuses,
  statusLabel,
  status,
  onStatusChange,
  onPageChange,
  children,
}: Readonly<AdminListProps<T>>) {
  const { t } = useTranslation();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;
  const filterId = useId();

  let empty: ReactNode;
  if (data && data.page > 0) {
    empty = (
      <>
        <p>{t('common.shell.list.emptyPage', { noun })}</p>
        <button type="button" onClick={() => onPageChange(0)}>
          {t('common.shell.list.backToFirstPage')}
        </button>
      </>
    );
  } else if (status) {
    empty = (
      <>
        <p>{t('common.shell.list.noMatch', { noun })}</p>
        <button type="button" onClick={() => onStatusChange('')}>
          {t('common.shell.list.clearFilter')}
        </button>
      </>
    );
  } else {
    empty = <p>{t('common.shell.list.empty', { noun })}</p>;
  }

  return (
    <>
      <div className="list-toolbar">
        <div className="field">
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
        <button type="button" onClick={() => query.refetch()}>
          {t('common.shell.list.refresh')}
        </button>
      </div>

      {disabled && (
        <output className="disabled-banner">{t('common.shell.list.disabled')}</output>
      )}
      {error && !disabled && (
        <div className="list-error">
          <p role="alert">{t('common.shell.list.loadError', { noun })}</p>
          <button type="button" onClick={() => query.refetch()}>
            {t('common.shell.list.retry')}
          </button>
        </div>
      )}
      {(query.isPending || query.isPlaceholderData) && (
        <output>{t('common.shell.list.loading', { noun })}</output>
      )}
      {data && error && <p className="stale-note">{t('common.shell.list.stale')}</p>}
      {data?.items.length === 0 && <div className="list-empty">{empty}</div>}
      {data && data.items.length > 0 && (
        <div className="table-wrapper" aria-busy={query.isPlaceholderData}>
          {children(data.items)}
        </div>
      )}
      {data && (data.items.length > 0 || data.page > 0) && (
        <Pager page={data.page} size={data.size} total={data.total} noun={noun} onPageChange={onPageChange} />
      )}
    </>
  );
}

function Pager({
  page,
  size,
  total,
  noun,
  onPageChange,
}: Readonly<{ page: number; size: number; total: number; noun: string; onPageChange: (page: number) => void }>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  // A page past the end (rows removed since the last page was read) still counts as a page.
  const pages = Math.max(1, Math.ceil(total / size), page + 1);
  const hasPrevious = page > 0;
  const hasNext = page + 1 < pages;

  // aria-disabled rather than disabled, so focus stays on the button when the last page is reached.
  return (
    <div className="pager">
      <button
        type="button"
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
          noun,
        })}
      </span>
      <button type="button" aria-disabled={!hasNext} onClick={() => hasNext && onPageChange(page + 1)}>
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
