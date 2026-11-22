import type { ListRunsParams, RunHistoryRowResponse, RunTrayResponse } from '@flow-cortex/api-client';

export type RunStatus = RunHistoryRowResponse['status'];

const STATUSES: readonly RunStatus[] = [
  'running',
  'waiting_approval',
  'completed',
  'error',
  'cancelling',
  'cancelled',
];

const FINISHED: ReadonlySet<RunStatus> = new Set(['completed', 'error', 'cancelled']);

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const LOCAL_DATE = /^(\d{4})-(\d{2})-(\d{2})$/;

/** Ho Chi Minh City has no daylight saving, so a fixed offset is exact. */
const HO_CHI_MINH_OFFSET_HOURS = 7;

/** The filters of the Runs page as the address holds them; dates are local `YYYY-MM-DD`, `page` is 1-based. */
export interface RunHistoryFilters {
  status?: RunStatus | undefined;
  workflowId?: string | undefined;
  from?: string | undefined;
  to?: string | undefined;
  favorite: boolean;
  page: number;
}

function validDate(value: string | null): string | undefined {
  const match = value === null ? null : LOCAL_DATE.exec(value);
  if (!match || value === null) return undefined;
  const [year, month, day] = [Number(match[1]), Number(match[2]), Number(match[3])];
  const date = new Date(Date.UTC(year, month - 1, day));
  const roundTrips =
    date.getUTCFullYear() === year && date.getUTCMonth() === month - 1 && date.getUTCDate() === day;
  return roundTrips ? value : undefined;
}

/** Reads the filters from the address; whatever is malformed is dropped rather than sent to the server. */
export function parse(search: URLSearchParams): RunHistoryFilters {
  const filters: RunHistoryFilters = { favorite: search.get('favorite') === '1', page: 1 };
  const status = search.get('status');
  if (status !== null && (STATUSES as readonly string[]).includes(status)) {
    filters.status = status as RunStatus;
  }
  const workflowId = search.get('workflowId');
  if (workflowId !== null && UUID.test(workflowId)) filters.workflowId = workflowId;
  const from = validDate(search.get('from'));
  const to = validDate(search.get('to'));
  if (from) filters.from = from;
  if (to && (!from || to >= from)) filters.to = to;
  const page = search.get('page');
  if (page !== null && /^\d+$/.test(page) && Number(page) >= 1) filters.page = Number(page);
  return filters;
}

/** The address form of the filters; defaults are left out so the bare page has a bare address. */
export function serialize(filters: RunHistoryFilters): URLSearchParams {
  const search = new URLSearchParams();
  if (filters.status) search.set('status', filters.status);
  if (filters.workflowId) search.set('workflowId', filters.workflowId);
  if (filters.from) search.set('from', filters.from);
  if (filters.to) search.set('to', filters.to);
  if (filters.favorite) search.set('favorite', '1');
  if (filters.page > 1) search.set('page', String(filters.page));
  return search;
}

/** UTC instant of 00:00 Ho Chi Minh time on the local date plus `plusDays`, written `…:00Z`. */
function dayStart(date: string, plusDays: number): string {
  const [year, month, day] = date.split('-').map(Number) as [number, number, number];
  const instant = new Date(Date.UTC(year, month - 1, day + plusDays, -HO_CHI_MINH_OFFSET_HOURS));
  return instant.toISOString().replace('.000Z', 'Z');
}

/** The server's list parameters: `from` is the start of its day, `to` the start of the day after. */
export function toListParams(filters: RunHistoryFilters, size: number): ListRunsParams {
  const params: ListRunsParams = { page: filters.page - 1, size };
  if (filters.status) params.status = filters.status;
  if (filters.workflowId) params.workflowId = filters.workflowId;
  if (filters.from) params.from = dayStart(filters.from, 0);
  if (filters.to) params.to = dayStart(filters.to, 1);
  if (filters.favorite) params.favorite = true;
  return params;
}

/** Only a finished run can be deleted; the server refuses the rest. */
export function canDeleteRun(status: RunStatus): boolean {
  return FINISHED.has(status);
}

export const TRAY_LIVE_INTERVAL_MS = 15_000;
export const TRAY_IDLE_INTERVAL_MS = 60_000;

/** How often the tray refetches: quickly while a run is live, slowly otherwise, never when signed out. */
export function trayRefetchInterval(
  tray: RunTrayResponse | undefined,
  signedIn: boolean,
): number | false {
  if (!signedIn) return false;
  return tray && tray.active.length > 0 ? TRAY_LIVE_INTERVAL_MS : TRAY_IDLE_INTERVAL_MS;
}
