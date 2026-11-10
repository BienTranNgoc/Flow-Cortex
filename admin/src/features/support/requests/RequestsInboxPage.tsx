import {
  ApiError,
  ListAdminSupportRequestsCreated,
  ListAdminSupportRequestsSort,
  ListAdminSupportRequestsStatus,
  ListAdminSupportRequestsType,
  type AdminSupportBulkAssignResult,
  type AdminSupportRequestFacets,
  type AdminSupportRequestSummary,
  type SupportBulkAssignFailureReason,
  type SupportFacetCount,
} from '@flow-cortex/api-client';
import { X } from '@phosphor-icons/react';
import type { TFunction } from 'i18next';
import { useEffect, useId, useMemo, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useSearchParams } from 'react-router-dom';
import { AdminList } from '../../../app/AdminList';
import { can } from '../../../app/permissions';
import { usePageTitle } from '../../../app/usePageTitle';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { useMeQuery } from '../../auth/useAuth';
import './requests.css';
import { RequestTime } from './RequestTime';
import { AssigneeLabel, RequesterLabel, StatusBadge } from './StatusBadge';
import { useAssignees } from './useAssignees';
import {
  useBulkAssign,
  useRequestFacets,
  useRequestsQuery,
  type RequestFilter,
} from './useAdminRequests';

const STATUSES = Object.values(ListAdminSupportRequestsStatus);
const TYPES = Object.values(ListAdminSupportRequestsType);
const CREATED = Object.values(ListAdminSupportRequestsCreated);
const SORTS = Object.values(ListAdminSupportRequestsSort);
const FAILURE_REASONS: readonly SupportBulkAssignFailureReason[] = [
  'not_found',
  'closed',
  'changed',
];
const SEARCH_DEBOUNCE_MS = 300;
const SEARCH_MAX_LENGTH = 100;
const MAX_PAGE = 10_000;
const ACCOUNT_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function oneOf<T extends string>(
  values: readonly T[],
  value: string | null | undefined,
): T | undefined {
  return values.find((candidate) => candidate === value);
}

/** Reads the filter from the URL; a value the server would refuse is dropped rather than sent. */
function readFilter(params: URLSearchParams): RequestFilter {
  const assignee = params.get('assignee');
  const q = params.get('q')?.trim().slice(0, SEARCH_MAX_LENGTH);
  const page = Number(params.get('page') ?? 0);
  return {
    status: oneOf(STATUSES, params.get('status')),
    type: oneOf(TYPES, params.get('type')),
    assignee:
      assignee === 'me' || assignee === 'unassigned' || (assignee && ACCOUNT_ID.test(assignee))
        ? assignee
        : undefined,
    created: oneOf(CREATED, params.get('created')),
    q: q || undefined,
    sort: oneOf(SORTS, params.get('sort')),
    page: Number.isInteger(page) && page > 0 ? Math.min(page, MAX_PAGE) : 0,
  };
}

function countOf(
  list: readonly SupportFacetCount[] | undefined,
  value: string,
): number | undefined {
  return list?.find((facet) => facet.value === value)?.count;
}

function withCount(t: TFunction, label: string, count: number | undefined): string {
  return count === undefined
    ? label
    : t('support.requests.withCount', { label, n: formatNumber(count, activeLocale()) });
}

type PendingOutcome =
  | { kind: 'done'; result: AdminSupportBulkAssignResult }
  | { kind: 'error'; message?: string | undefined };
type Outcome = PendingOutcome & { scope: string };

export function RequestsInboxPage() {
  const { t } = useTranslation();
  usePageTitle(t('support.requests.inboxTitle'));
  const [params, setParams] = useSearchParams();
  const scope = params.toString();
  const filter = useMemo(() => readFilter(params), [params]);
  const canAssign = can(useMeQuery().data, 'support.ticket.assign');
  const query = useRequestsQuery(filter);
  const facets = useRequestFacets(filter).data;
  const [picked, setPicked] = useState<{ scope: string; numbers: number[] }>({
    scope,
    numbers: [],
  });
  const [outcome, setOutcome] = useState<Outcome | null>(null);

  const rows = query.data?.items;
  // A selection belongs to the page it was made on: another filter or page, or a row that left it, drops out.
  const selected = useMemo(
    () =>
      picked.scope === scope && rows
        ? picked.numbers.filter((number) => rows.some((row) => row.number === number))
        : [],
    [picked, scope, rows],
  );

  const change = (patch: Partial<RequestFilter>) => {
    setParams(
      (previous) => {
        const next = new URLSearchParams(previous);
        const merged: Record<string, unknown> = { ...readFilter(previous), page: 0, ...patch };
        for (const [key, value] of Object.entries(merged)) {
          if (value === undefined || value === '' || (key === 'page' && value === 0)) {
            next.delete(key);
          } else {
            next.set(key, String(value));
          }
        }
        return next;
      },
      { replace: true },
    );
  };

  const filtered = Boolean(filter.type || filter.assignee || filter.created || filter.q);
  const statusLabel = (value: string) =>
    oneOf(STATUSES, value)
      ? withCount(t, t(`support.requests.status.${value}`), countOf(facets?.status, value))
      : value;
  const visibleOutcome = outcome?.scope === scope ? outcome : null;

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('support.requests.eyebrow')}</p>
          <h1>{t('support.requests.inboxTitle')}</h1>
          <p className="cortex-admin-page-description">{t('support.requests.description')}</p>
        </div>
      </div>

      <div className="request-inbox">
        <InboxToolbar
          filter={filter}
          facets={facets}
          anyFilter={filtered || Boolean(filter.status)}
          onChange={change}
          onClear={() => setParams({}, { replace: true })}
        />

        <AdminList
          query={query}
          nounKey="support.requests.noun"
          tableLabelKey="support.requests.tableLabel"
          statuses={STATUSES}
          statusLabel={statusLabel}
          status={filter.status ?? ''}
          filtered={filtered}
          onStatusChange={(next) => change({ status: oneOf(STATUSES, next) })}
          onPageChange={(page) => change({ page })}
        >
          {(requests) => (
            <RequestsTable
              requests={requests}
              selectable={canAssign}
              selected={selected}
              onSelect={(numbers) => setPicked({ scope, numbers })}
            />
          )}
        </AdminList>

        {canAssign && (rows?.length || visibleOutcome) ? (
          <BulkBar
            pageNumbers={rows?.map((row) => row.number) ?? []}
            selected={selected}
            outcome={visibleOutcome}
            onSelect={(numbers) => setPicked({ scope, numbers })}
            onOutcome={(next) => setOutcome(next && { ...next, scope })}
          />
        ) : null}
      </div>
    </main>
  );
}

function InboxToolbar({
  filter,
  facets,
  anyFilter,
  onChange,
  onClear,
}: Readonly<{
  filter: RequestFilter;
  facets: AdminSupportRequestFacets | undefined;
  anyFilter: boolean;
  onChange: (patch: Partial<RequestFilter>) => void;
  onClear: () => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const searchRef = useRef<HTMLInputElement>(null);
  const [draft, setDraft] = useState(filter.q ?? '');
  const committed = useRef(filter.q ?? '');
  const urlQuery = filter.q ?? '';

  // The URL changed without this field (a cleared filter, a shared link): show it.
  useEffect(() => {
    if (urlQuery !== committed.current) {
      committed.current = urlQuery;
      setDraft(urlQuery);
    }
  }, [urlQuery]);

  const commit = (value: string) => {
    const next = value.trim();
    if (next === committed.current) return;
    committed.current = next;
    onChange({ q: next || undefined });
  };

  const latestCommit = useRef(commit);
  latestCommit.current = commit;
  useEffect(() => {
    const timer = setTimeout(() => latestCommit.current(draft), SEARCH_DEBOUNCE_MS);
    return () => clearTimeout(timer);
  }, [draft]);

  const assignee = facets?.assignee;
  const people = assignee?.people ?? [];
  const knownPerson = people.some((person) => person.id === filter.assignee);
  const otherAssignee =
    filter.assignee && filter.assignee !== 'me' && filter.assignee !== 'unassigned' && !knownPerson;

  return (
    <form
      className="cortex-admin-toolbar request-toolbar"
      role="search"
      aria-label={t('support.requests.filtersLabel')}
      onSubmit={(event) => {
        event.preventDefault();
        commit(draft);
      }}
    >
      <div className="cortex-admin-field request-search">
        <label htmlFor={`${ids}-q`}>{t('support.requests.search')}</label>
        <div className="request-search-box">
          <input
            ref={searchRef}
            id={`${ids}-q`}
            type="text"
            enterKeyHint="search"
            autoComplete="off"
            maxLength={SEARCH_MAX_LENGTH}
            placeholder={t('support.requests.searchPlaceholder')}
            value={draft}
            onChange={(event) => setDraft(event.target.value)}
          />
          {draft && (
            <button
              type="button"
              className="request-search-clear"
              aria-label={t('support.requests.clearSearch')}
              onClick={() => {
                setDraft('');
                commit('');
                searchRef.current?.focus();
              }}
            >
              <X aria-hidden="true" size={16} />
            </button>
          )}
        </div>
      </div>

      <div className="cortex-admin-field">
        <label htmlFor={`${ids}-assignee`}>{t('support.requests.columns.assignee')}</label>
        <select
          id={`${ids}-assignee`}
          value={filter.assignee ?? ''}
          onChange={(event) => onChange({ assignee: event.target.value || undefined })}
        >
          <option value="">{t('support.requests.allAssignees')}</option>
          <option value="me">
            {withCount(t, t('support.requests.assignedToMe'), assignee?.mine)}
          </option>
          <option value="unassigned">
            {withCount(t, t('support.requests.unassigned'), assignee?.unassigned)}
          </option>
          {people.map((person) => (
            <option key={person.id} value={person.id}>
              {withCount(t, person.email ?? t('support.requests.assigneeDeleted'), person.count)}
            </option>
          ))}
          {otherAssignee && filter.assignee && (
            <option value={filter.assignee}>{t('support.requests.otherAssignee')}</option>
          )}
        </select>
      </div>

      <div className="cortex-admin-field">
        <label htmlFor={`${ids}-created`}>{t('support.requests.createdFilter')}</label>
        <select
          id={`${ids}-created`}
          value={filter.created ?? ''}
          onChange={(event) => onChange({ created: oneOf(CREATED, event.target.value) })}
        >
          <option value="">{t('support.requests.allCreated')}</option>
          {CREATED.map((value) => (
            <option key={value} value={value}>
              {withCount(
                t,
                t(`support.requests.created.${value}`),
                countOf(facets?.created, value),
              )}
            </option>
          ))}
        </select>
      </div>

      <div className="cortex-admin-field">
        <label htmlFor={`${ids}-type`}>{t('support.requests.typeFilter')}</label>
        <select
          id={`${ids}-type`}
          value={filter.type ?? ''}
          onChange={(event) => onChange({ type: oneOf(TYPES, event.target.value) })}
        >
          <option value="">{t('support.requests.allTypes')}</option>
          {TYPES.map((value) => (
            <option key={value} value={value}>
              {withCount(t, t(`support.requests.type.${value}`), countOf(facets?.type, value))}
            </option>
          ))}
        </select>
      </div>

      <div className="cortex-admin-field">
        <label htmlFor={`${ids}-sort`}>{t('support.requests.sortLabel')}</label>
        <select
          id={`${ids}-sort`}
          value={filter.sort ?? ListAdminSupportRequestsSort.newest}
          onChange={(event) => onChange({ sort: oneOf(SORTS, event.target.value) })}
        >
          {SORTS.map((value) => (
            <option key={value} value={value}>
              {t(`support.requests.sort.${value}`)}
            </option>
          ))}
        </select>
      </div>

      {anyFilter && (
        <button type="button" className="cortex-admin-btn" onClick={onClear}>
          {t('support.requests.clearFilters')}
        </button>
      )}
    </form>
  );
}

function RequestsTable({
  requests,
  selectable,
  selected,
  onSelect,
}: Readonly<{
  requests: AdminSupportRequestSummary[];
  selectable: boolean;
  selected: number[];
  onSelect: (numbers: number[]) => void;
}>) {
  const { t } = useTranslation();
  const toggle = (number: number, on: boolean) =>
    onSelect(on ? [...selected, number] : selected.filter((value) => value !== number));
  return (
    <table className="cortex-admin-table request-table">
      <thead>
        <tr>
          {selectable && (
            <th scope="col" className="request-select-col">
              <span className="request-visually-hidden">
                {t('support.requests.columns.select')}
              </span>
            </th>
          )}
          <th scope="col">{t('support.requests.columns.number')}</th>
          <th scope="col">{t('support.requests.columns.subject')}</th>
          <th scope="col">{t('support.requests.columns.type')}</th>
          <th scope="col">{t('support.requests.columns.status')}</th>
          <th scope="col">{t('support.requests.columns.assignee')}</th>
          <th scope="col">{t('support.requests.columns.requester')}</th>
          <th scope="col">{t('support.requests.columns.activity')}</th>
        </tr>
      </thead>
      <tbody>
        {requests.map((request) => (
          <tr key={request.number}>
            {selectable && (
              <td className="request-select-col">
                <input
                  type="checkbox"
                  className="request-checkbox"
                  aria-label={t('support.requests.selectRow', { number: request.number })}
                  checked={selected.includes(request.number)}
                  onChange={(event) => toggle(request.number, event.target.checked)}
                />
              </td>
            )}
            <td data-label={t('support.requests.columns.number')}>
              {t('support.requests.number', { number: request.number })}
            </td>
            <td data-label={t('support.requests.columns.subject')}>
              <Link to={`/support/requests/${request.number}`}>{request.subject}</Link>
            </td>
            <td data-label={t('support.requests.columns.type')}>
              {t(`support.requests.type.${request.type}`)}
            </td>
            <td data-label={t('support.requests.columns.status')}>
              <StatusBadge status={request.status} />
            </td>
            <td data-label={t('support.requests.columns.assignee')}>
              <AssigneeLabel assignee={request.assignee} />
            </td>
            <td data-label={t('support.requests.columns.requester')}>
              <RequesterLabel requester={request.requester} />
            </td>
            <td data-label={t('support.requests.columns.activity')}>
              <RequestTime value={request.lastActivityAt} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

function resultText(t: TFunction, result: AdminSupportBulkAssignResult): string {
  const parts: string[] = [];
  if (result.assigned.length > 0) {
    parts.push(t('support.requests.bulk.assigned', { count: result.assigned.length }));
  }
  if (result.unchanged.length > 0) {
    parts.push(t('support.requests.bulk.unchanged', { count: result.unchanged.length }));
  }
  if (result.failed.length > 0) {
    const list = result.failed
      .map((failure) =>
        t(
          `support.requests.bulk.reason.${FAILURE_REASONS.includes(failure.reason) ? failure.reason : 'other'}`,
          { number: failure.number },
        ),
      )
      .join('; ');
    parts.push(t('support.requests.bulk.failed', { count: result.failed.length, list }));
  }
  return parts.join(', ');
}

function BulkBar({
  pageNumbers,
  selected,
  outcome,
  onSelect,
  onOutcome,
}: Readonly<{
  pageNumbers: number[];
  selected: number[];
  outcome: Outcome | null;
  onSelect: (numbers: number[]) => void;
  onOutcome: (next: PendingOutcome | null) => void;
}>) {
  const { t } = useTranslation();
  const ids = useId();
  const assignees = useAssignees(true);
  const bulk = useBulkAssign();
  const [assigneeId, setAssigneeId] = useState('');
  const allRef = useRef<HTMLInputElement>(null);
  const allSelected = pageNumbers.length > 0 && selected.length === pageNumbers.length;

  useEffect(() => {
    if (allRef.current) allRef.current.indeterminate = selected.length > 0 && !allSelected;
  }, [selected.length, allSelected]);

  const ready = selected.length > 0 && assigneeId !== '' && !bulk.isPending;

  const assign = () => {
    if (!ready) return;
    onOutcome(null);
    bulk.mutate(
      { numbers: selected, assigneeId },
      {
        onSuccess: (result) => {
          onOutcome({ kind: 'done', result });
          onSelect([]);
        },
        onError: (error) =>
          onOutcome({
            kind: 'error',
            message: error instanceof ApiError ? error.problem?.errors?.assigneeId : undefined,
          }),
      },
    );
  };

  return (
    <section className="request-bulk" aria-label={t('support.requests.bulk.label')}>
      <label className="request-bulk-all">
        <input
          ref={allRef}
          type="checkbox"
          className="request-checkbox"
          checked={allSelected}
          onChange={(event) => onSelect(event.target.checked ? pageNumbers : [])}
        />
        {t('support.requests.selectPage')}
      </label>
      <span className="request-bulk-count">
        {t('support.requests.selectedCount', { count: selected.length })}
      </span>
      <div className="cortex-admin-field request-bulk-picker">
        <label htmlFor={`${ids}-assignee`}>{t('support.requests.bulk.assignTo')}</label>
        <select
          id={`${ids}-assignee`}
          value={assigneeId}
          onChange={(event) => setAssigneeId(event.target.value)}
        >
          <option value="" disabled>
            {t('support.requests.pickAssignee')}
          </option>
          {assignees.data?.map((person) => (
            <option key={person.id} value={person.id}>
              {person.email}
            </option>
          ))}
        </select>
      </div>
      <button
        type="button"
        className="cortex-admin-btn cortex-admin-btn--primary"
        aria-disabled={!ready}
        onClick={assign}
      >
        {bulk.isPending ? t('support.common.saving') : t('support.requests.bulk.assign')}
      </button>
      {assignees.isError && (
        <p className="cortex-admin-form-error request-bulk-note" role="alert">
          {t('support.requests.assigneesFailed')}
        </p>
      )}
      <p className="request-bulk-result" role="status">
        {outcome?.kind === 'done' ? resultText(t, outcome.result) : ''}
      </p>
      {outcome?.kind === 'error' && (
        <p className="cortex-admin-form-error request-bulk-note" role="alert">
          {outcome.message ?? t('support.requests.bulk.error')}
        </p>
      )}
    </section>
  );
}
