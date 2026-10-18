import { ApiError } from '@flow-cortex/api-client';
import type { UseQueryResult } from '@tanstack/react-query';
import type { ReactNode } from 'react';

export interface Page<T> {
  items: T[];
  page: number;
  size: number;
  total: number;
}

interface AdminListProps<T> {
  query: UseQueryResult<Page<T>>;
  /** Plural, lower case: "runs", "media jobs". */
  noun: string;
  statuses: readonly string[];
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
  status,
  onStatusChange,
  onPageChange,
  children,
}: Readonly<AdminListProps<T>>) {
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;
  const filterId = `${noun.replace(/\s+/g, '-')}-status`;

  let empty: ReactNode;
  if (data && data.page > 0) {
    empty = (
      <>
        <p>No {noun} on this page.</p>
        <button type="button" className="cortex-admin-btn" onClick={() => onPageChange(0)}>
          Back to first page
        </button>
      </>
    );
  } else if (status) {
    empty = (
      <>
        <p>No {noun} match this filter</p>
        <button type="button" className="cortex-admin-btn" onClick={() => onStatusChange('')}>
          Clear filter
        </button>
      </>
    );
  } else {
    empty = <p>No {noun} yet.</p>;
  }

  return (
    <>
      <div className="cortex-admin-toolbar">
        <div className="cortex-admin-field">
          <label htmlFor={filterId}>Status</label>
          <select id={filterId} value={status} onChange={(event) => onStatusChange(event.target.value)}>
            <option value="">All statuses</option>
            {statuses.map((value) => (
              <option key={value} value={value}>
                {value}
              </option>
            ))}
          </select>
        </div>
        <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
          Refresh
        </button>
      </div>

      {disabled && (
        <output className="cortex-admin-disabled-banner">Admin console is disabled</output>
      )}
      {error && !disabled && (
        <div className="cortex-admin-error">
          <p role="alert">Could not load {noun}.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
            Retry
          </button>
        </div>
      )}
      {(query.isPending || query.isPlaceholderData) && (
        <output className="cortex-admin-loading">Loading {noun}…</output>
      )}
      {data && error && (
        <p className="cortex-admin-stale">Showing the results loaded before this error.</p>
      )}
      {data?.items.length === 0 && <div className="cortex-admin-empty">{empty}</div>}
      {data && data.items.length > 0 && (
        <div className="cortex-admin-table-wrapper" aria-busy={query.isPlaceholderData}>
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
        Previous page
      </button>
      <span>
        Page {page + 1} of {pages} · {total} {noun}
      </span>
      <button
        type="button"
        className="cortex-admin-btn"
        aria-disabled={!hasNext}
        onClick={() => hasNext && onPageChange(page + 1)}
      >
        Next page
      </button>
    </div>
  );
}

/** A timestamp cell's content: the local time, or a dash when the row has none. */
export function Timestamp({ value }: Readonly<{ value: string | undefined }>) {
  return value ? <time dateTime={value}>{new Date(value).toLocaleString()}</time> : <>—</>;
}
