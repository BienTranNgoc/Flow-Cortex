import { ApiError, type AuditLogItem } from '@flow-cortex/api-client';
import { ArrowsClockwise, DownloadSimple, X } from '@phosphor-icons/react';
import { useEffect, useMemo, useRef, useState, type MouseEvent, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Timestamp } from '../../app/AdminList';
import { EmptyState } from '../../app/EmptyState';
import { PageHeading } from '../../app/PageHeading';
import { can } from '../../app/permissions';
import { StatusPill } from '../../app/StatusPill';
import { TableRegion } from '../../app/TableRegion';
import { usePageTitle } from '../../app/usePageTitle';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { actorName } from './auditActor';
import { AuditDetailPanel } from './AuditDetailPanel';
import {
  AUDIT_STATUSES,
  EMPTY_AUDIT_FILTERS,
  MAX_SEARCH_LENGTH,
  MIN_SEARCH_LENGTH,
  isFiltered,
  rangeIsReversed,
  sendableSearch,
  type AuditFilters,
} from './auditFilters';
import { useAuditFacetsQuery, useAuditLogQuery, useExportAuditLog } from './useAuditLog';
import './audit.css';

const SEARCH_DEBOUNCE_MS = 300;

type AuditRow = AuditLogItem & { id: string };

function hasId(item: AuditLogItem): item is AuditRow {
  return item.id !== undefined;
}

function withCurrent(values: readonly string[] | undefined, current: string): string[] {
  const list = values ?? [];
  return current !== '' && !list.includes(current) ? [current, ...list] : [...list];
}

function exportFailure(error: unknown, t: (key: string) => string): string {
  if (error instanceof ApiError) {
    if ((error.status === 422 || error.status === 503) && error.problem?.detail) {
      return error.problem.detail;
    }
    if (error.status === 422) return t('audit.export.tooLarge');
    if (error.status === 503) return t('audit.export.unavailable');
    if (error.status === 403) return t('audit.export.forbidden');
  }
  return t('audit.export.failed');
}

export function AuditLogPage() {
  const { t } = useTranslation();
  usePageTitle(t('audit.title'));
  const me = useMeQuery().data;
  const canExport = can(me, 'system.audit_log.export');
  const [filters, setFilters] = useState<AuditFilters>(EMPTY_AUDIT_FILTERS);
  const [actorChip, setActorChip] = useState<string | undefined>();
  const [draft, setDraft] = useState('');
  const [selectedId, setSelectedId] = useState<string | undefined>();
  const query = useAuditLogQuery(filters);
  const facetsQuery = useAuditFacetsQuery();
  const exportMutation = useExportAuditLog();
  const { reset: resetExport } = exportMutation;
  const lastOpened = useRef<string | undefined>();

  const wanted = sendableSearch(draft);
  const typedLength = draft.trim().length;
  const reversed = rangeIsReversed(filters);
  const filtered = isFiltered(filters);

  const change = (patch: Partial<AuditFilters>) => {
    setFilters((previous) => ({ ...previous, ...patch }));
    setSelectedId(undefined);
    resetExport();
  };

  useEffect(() => {
    if (wanted === filters.q) return undefined;
    const timer = setTimeout(() => {
      setFilters((previous) => ({ ...previous, q: wanted }));
      setSelectedId(undefined);
      resetExport();
    }, SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [wanted, filters.q, resetExport]);

  const rows = useMemo(
    () => (query.data?.pages ?? []).flatMap((page) => page.items ?? []).filter(hasId),
    [query.data],
  );
  const selected = rows.find((row) => row.id === selectedId);

  const clearFilters = () => {
    setDraft('');
    setActorChip(undefined);
    setFilters(EMPTY_AUDIT_FILTERS);
    setSelectedId(undefined);
    resetExport();
  };

  const filterByActor = (row: AuditLogItem) => {
    if (!row.actorUserId) return;
    setActorChip(actorName(row));
    change({ actorUserId: row.actorUserId });
  };

  const open = (row: AuditRow) => {
    lastOpened.current = row.id;
    setSelectedId(row.id);
  };

  const closeDetails = () => {
    setSelectedId(undefined);
    const id = lastOpened.current;
    if (id !== undefined) {
      requestAnimationFrame(() => document.getElementById(`audit-open-${id}`)?.focus());
    }
  };

  const failedFirstLoad = query.isError && !query.isFetchNextPageError && rows.length === 0;
  let exportMessage: string | undefined;
  if (exportMutation.isError) {
    exportMessage = exportFailure(exportMutation.error, t);
  } else if (exportMutation.isSuccess) {
    exportMessage = t('audit.export.done', { fileName: exportMutation.data });
  }

  let body: ReactNode = null;
  if (reversed) {
    body = null;
  } else if (failedFirstLoad) {
    body = (
      <div className="cortex-admin-error" role="alert">
        <p>{t('audit.loadError')}</p>
        <button type="button" className="cortex-admin-btn" onClick={() => void query.refetch()}>
          {t('audit.retry')}
        </button>
      </div>
    );
  } else if (query.isPending) {
    body = <output className="cortex-admin-loading">{t('audit.loading')}</output>;
  } else if (rows.length === 0) {
    body = filtered ? (
      <EmptyState
        title={t('audit.noMatch')}
        action={
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--ghost"
            onClick={clearFilters}
          >
            {t('audit.filters.clear')}
          </button>
        }
      />
    ) : (
      <EmptyState title={t('audit.empty.title')} description={t('audit.empty.hint')} />
    );
  } else {
    body = (
      <>
        {query.isRefetchError && <p className="cortex-admin-stale">{t('audit.stale')}</p>}
        <TableRegion label={t('audit.table.label')} busy={query.isPlaceholderData}>
          <table className="cortex-admin-table audit-table">
            <thead>
              <tr>
                <th scope="col">{t('audit.columns.time')}</th>
                <th scope="col">{t('audit.columns.actor')}</th>
                <th scope="col">{t('audit.columns.action')}</th>
                <th scope="col">{t('audit.columns.resource')}</th>
                <th scope="col">{t('audit.columns.status')}</th>
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                <AuditTableRow
                  key={row.id}
                  row={row}
                  selected={row.id === selectedId}
                  onOpen={open}
                  onFilterActor={filterByActor}
                />
              ))}
            </tbody>
          </table>
        </TableRegion>
        {query.isFetchNextPageError && (
          <p className="cortex-admin-form-error" role="alert">
            {t('audit.loadMoreError')}
          </p>
        )}
        {query.hasNextPage && (
          <div className="audit-more">
            <button
              type="button"
              className="cortex-admin-btn"
              disabled={query.isFetchingNextPage}
              onClick={() => void query.fetchNextPage()}
            >
              {query.isFetchingNextPage ? t('audit.loadingMore') : t('audit.loadMore')}
            </button>
          </div>
        )}
      </>
    );
  }

  const facets = facetsQuery.data;

  return (
    <div className={`audit-page${selected ? ' audit-page--detail' : ''}`}>
      <PageHeading
        eyebrow={t('audit.eyebrow')}
        title={t('audit.title')}
        description={t('audit.description')}
        actions={
          <>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={() => void query.refetch()}
              disabled={query.isFetching || reversed}
            >
              <ArrowsClockwise size={16} aria-hidden="true" />
              <span>{query.isFetching ? t('audit.refreshing') : t('audit.refresh')}</span>
            </button>
            {canExport && (
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--primary"
                onClick={() => exportMutation.mutate(filters)}
                disabled={exportMutation.isPending || reversed}
              >
                <DownloadSimple size={16} aria-hidden="true" />
                <span>
                  {exportMutation.isPending ? t('audit.export.busy') : t('audit.export.button')}
                </span>
              </button>
            )}
          </>
        }
      />

      {exportMessage !== undefined &&
        (exportMutation.isError ? (
          <div className="cortex-admin-error audit-notice" role="alert">
            <p>{exportMessage}</p>
          </div>
        ) : (
          <p className="cortex-admin-form-success audit-notice" role="status">
            {exportMessage}
          </p>
        ))}

      <div className="audit-workspace">
        <section className="cortex-admin-panel audit-main" aria-label={t('audit.title')}>
          <div className="audit-filters" role="search" aria-label={t('audit.filters.label')}>
            <div className="cortex-admin-field audit-filter audit-filter--search">
              <label htmlFor="audit-search">{t('audit.filters.search')}</label>
              <input
                id="audit-search"
                type="search"
                value={draft}
                maxLength={MAX_SEARCH_LENGTH}
                onChange={(event) => setDraft(event.target.value)}
                placeholder={t('audit.filters.searchPlaceholder')}
                aria-describedby={
                  typedLength > 0 && typedLength < MIN_SEARCH_LENGTH
                    ? 'audit-search-hint'
                    : undefined
                }
              />
            </div>
            <div className="cortex-admin-field audit-filter">
              <label htmlFor="audit-action">{t('audit.filters.action')}</label>
              <select
                id="audit-action"
                value={filters.action}
                onChange={(event) => change({ action: event.target.value })}
              >
                <option value="">{t('audit.filters.allActions')}</option>
                {withCurrent(facets?.actions, filters.action).map((action) => (
                  <option key={action} value={action}>
                    {action}
                  </option>
                ))}
              </select>
            </div>
            <div className="cortex-admin-field audit-filter">
              <label htmlFor="audit-resource-type">{t('audit.filters.resourceType')}</label>
              <select
                id="audit-resource-type"
                value={filters.resourceType}
                onChange={(event) => change({ resourceType: event.target.value })}
              >
                <option value="">{t('audit.filters.allResourceTypes')}</option>
                {withCurrent(facets?.resourceTypes, filters.resourceType).map((type) => (
                  <option key={type} value={type}>
                    {type}
                  </option>
                ))}
              </select>
            </div>
            <div className="cortex-admin-field audit-filter">
              <label htmlFor="audit-status">{t('audit.filters.status')}</label>
              <select
                id="audit-status"
                value={filters.status}
                onChange={(event) =>
                  change({ status: AUDIT_STATUSES.find((s) => s === event.target.value) ?? '' })
                }
              >
                <option value="">{t('audit.filters.allStatuses')}</option>
                {AUDIT_STATUSES.map((status) => (
                  <option key={status} value={status}>
                    {t(`audit.status.${status}`)}
                  </option>
                ))}
              </select>
            </div>
            <div className="cortex-admin-field audit-filter audit-filter--date">
              <label htmlFor="audit-from">{t('audit.filters.from')}</label>
              <input
                id="audit-from"
                type="date"
                value={filters.fromDate}
                max={filters.toDate || undefined}
                aria-invalid={reversed}
                onChange={(event) => change({ fromDate: event.target.value })}
              />
            </div>
            <div className="cortex-admin-field audit-filter audit-filter--date">
              <label htmlFor="audit-to">{t('audit.filters.to')}</label>
              <input
                id="audit-to"
                type="date"
                value={filters.toDate}
                min={filters.fromDate || undefined}
                aria-invalid={reversed}
                onChange={(event) => change({ toDate: event.target.value })}
              />
            </div>
          </div>

          {typedLength > 0 && typedLength < MIN_SEARCH_LENGTH && (
            <p id="audit-search-hint" className="audit-hint">
              {t('audit.filters.searchHint', { count: MIN_SEARCH_LENGTH })}
            </p>
          )}
          {reversed && (
            <p className="cortex-admin-form-error" role="alert">
              {t('audit.filters.rangeReversed')}
            </p>
          )}
          {facetsQuery.isError && (
            <p className="audit-hint" role="status">
              {t('audit.filters.facetsError')}{' '}
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--sm"
                onClick={() => void facetsQuery.refetch()}
              >
                {t('audit.filters.facetsRetry')}
              </button>
            </p>
          )}
          {filtered && (
            <div className="audit-active">
              {filters.actorUserId !== '' && (
                <span className="audit-chip">
                  <span>
                    {t('audit.filters.actorChip', { name: actorChip ?? filters.actorUserId })}
                  </span>
                  <button
                    type="button"
                    className="audit-chip__remove"
                    aria-label={t('audit.filters.removeActor', {
                      name: actorChip ?? filters.actorUserId,
                    })}
                    onClick={() => {
                      setActorChip(undefined);
                      change({ actorUserId: '' });
                    }}
                  >
                    <X size={14} aria-hidden="true" />
                  </button>
                </span>
              )}
              {filtered && (
                <button
                  type="button"
                  className="cortex-admin-btn cortex-admin-btn--ghost cortex-admin-btn--sm"
                  onClick={clearFilters}
                >
                  {t('audit.filters.clear')}
                </button>
              )}
            </div>
          )}

          {body}
        </section>
        {selected && (
          <AuditDetailPanel item={selected} onClose={closeDetails} onFilterActor={filterByActor} />
        )}
      </div>
    </div>
  );
}

interface AuditTableRowProps {
  readonly row: AuditRow;
  readonly selected: boolean;
  readonly onOpen: (row: AuditRow) => void;
  readonly onFilterActor: (row: AuditLogItem) => void;
}

function AuditTableRow({ row, selected, onOpen, onFilterActor }: Readonly<AuditTableRowProps>) {
  const { t } = useTranslation();
  const failed = row.status === 'failed';
  const actor = actorName(row);
  const resource = row.resourceLabel ?? row.resourceId;
  const when = row.occurredAt
    ? formatDateTime(row.occurredAt, activeLocale(), { dateStyle: 'short', timeStyle: 'medium' })
    : '';
  const filterActor = (event: MouseEvent) => {
    event.stopPropagation();
    onFilterActor(row);
  };

  return (
    <tr
      className={selected ? 'audit-row audit-row--selected' : 'audit-row'}
      onClick={() => onOpen(row)}
    >
      <td data-label={t('audit.columns.time')}>
        <button
          type="button"
          id={`audit-open-${row.id}`}
          className="audit-row__open"
          aria-pressed={selected}
          aria-label={t('audit.table.openDetails', { action: row.action ?? '', time: when })}
        >
          <Timestamp value={row.occurredAt} />
        </button>
      </td>
      <td data-label={t('audit.columns.actor')}>
        {row.actorUserId ? (
          <button
            type="button"
            className="cortex-admin-link audit-row__actor"
            aria-label={t('audit.table.filterByActor', {
              name: actor ?? t('audit.table.unknownActor'),
            })}
            onClick={filterActor}
          >
            {actor ?? t('audit.table.unknownActor')}
          </button>
        ) : (
          (actor ?? t('audit.table.unknownActor'))
        )}
        {row.actorName && row.actorEmail && <span className="audit-sub">{row.actorEmail}</span>}
      </td>
      <td data-label={t('audit.columns.action')}>
        <code className="audit-code">{row.action}</code>
      </td>
      <td data-label={t('audit.columns.resource')}>
        <span>{row.resourceType}</span>
        {resource && <span className="audit-sub">{resource}</span>}
      </td>
      <td data-label={t('audit.columns.status')}>
        <StatusPill
          tone={failed ? 'error' : 'success'}
          label={t(failed ? 'audit.status.failed' : 'audit.status.success')}
        />
      </td>
    </tr>
  );
}
