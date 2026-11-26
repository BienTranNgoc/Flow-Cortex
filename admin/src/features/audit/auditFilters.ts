import type { ExportAuditLogParams } from '@flow-cortex/api-client';

export const AUDIT_PAGE_SIZE = 50;
export const MIN_SEARCH_LENGTH = 3;
export const MAX_SEARCH_LENGTH = 100;
export const AUDIT_STATUSES = ['success', 'failed'] as const;

export type AuditStatus = (typeof AUDIT_STATUSES)[number];

/** What the filter bar holds; `fromDate` and `toDate` are the `YYYY-MM-DD` values of the date inputs. */
export interface AuditFilters {
  readonly q: string;
  readonly action: string;
  readonly resourceType: string;
  readonly actorUserId: string;
  readonly status: AuditStatus | '';
  readonly fromDate: string;
  readonly toDate: string;
}

export const EMPTY_AUDIT_FILTERS: AuditFilters = {
  q: '',
  action: '',
  resourceType: '',
  actorUserId: '',
  status: '',
  fromDate: '',
  toDate: '',
};

const DAY_MS = 86_400_000;
const DATE_PATTERN = /^\d{4}-\d{2}-\d{2}$/;

function utcDayStart(date: string): number | undefined {
  if (!DATE_PATTERN.test(date)) return undefined;
  const instant = Date.parse(`${date}T00:00:00.000Z`);
  return Number.isNaN(instant) ? undefined : instant;
}

/** A search the server accepts: shorter text is held back, not sent. */
export function sendableSearch(typed: string): string {
  const trimmed = typed.trim().slice(0, MAX_SEARCH_LENGTH).trim();
  return trimmed.length >= MIN_SEARCH_LENGTH ? trimmed : '';
}

/** The chosen days name a range the server would refuse: it ends at or before its start. */
export function rangeIsReversed(filters: Pick<AuditFilters, 'fromDate' | 'toDate'>): boolean {
  const from = utcDayStart(filters.fromDate);
  const to = utcDayStart(filters.toDate);
  return from !== undefined && to !== undefined && from >= to + DAY_MS;
}

/** Filters as the list and the export take them: empty values left out, days as UTC instants. */
export function toRequestParams(filters: AuditFilters): ExportAuditLogParams {
  const from = utcDayStart(filters.fromDate);
  const to = utcDayStart(filters.toDate);
  return {
    ...(filters.q ? { q: filters.q } : {}),
    ...(filters.action ? { action: filters.action } : {}),
    ...(filters.resourceType ? { resourceType: filters.resourceType } : {}),
    ...(filters.actorUserId ? { actorUserId: filters.actorUserId } : {}),
    ...(filters.status ? { status: filters.status } : {}),
    ...(from === undefined ? {} : { from: new Date(from).toISOString() }),
    ...(to === undefined ? {} : { to: new Date(to + DAY_MS).toISOString() }),
  };
}

export function isFiltered(filters: AuditFilters): boolean {
  return Object.values(filters).some((value) => value !== '');
}
