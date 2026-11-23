import type { ListDriveMemoryParams } from '@flow-cortex/api-client';

// The generated params type these as plain strings, so typecheck cannot catch a drifted value.
export const SORTS = ['newest', 'oldest', 'size_desc', 'size_asc'] as const;
export const TIMES = ['all', '7d', '30d', 'custom'] as const;
export const SIZES = ['lt1mb', '1to50mb', 'gt50mb'] as const;
export const TYPES = ['text', 'image', 'video', 'audio'] as const;
export const PAGE_SIZES = [20, 50, 100] as const;

export type MemorySort = (typeof SORTS)[number];
export type MemoryTime = (typeof TIMES)[number];
export type MemorySize = (typeof SIZES)[number];
export type MemoryType = (typeof TYPES)[number];

export interface MemoryQuery {
  q: string;
  types: MemoryType[];
  time: MemoryTime;
  /** `YYYY-MM-DD` from a date input, or `''`; read only while `time` is `custom`. */
  from: string;
  to: string;
  sizes: MemorySize[];
  sort: MemorySort;
  /** 0-based, as the API. */
  page: number;
  pageSize: number;
}

export const initialMemoryQuery: MemoryQuery = {
  q: '',
  types: [],
  time: 'all',
  from: '',
  to: '',
  sizes: [],
  sort: 'newest',
  page: 0,
  pageSize: 20,
};

export type MemoryQueryAction =
  | { type: 'search'; q: string }
  | {
      type: 'filters';
      types: MemoryType[];
      time: MemoryTime;
      from: string;
      to: string;
      sizes: MemorySize[];
    }
  | { type: 'sort'; sort: MemorySort }
  | { type: 'pageSize'; pageSize: number }
  | { type: 'page'; page: number }
  | { type: 'clear' };

export function memoryQueryReducer(state: MemoryQuery, action: MemoryQueryAction): MemoryQuery {
  switch (action.type) {
    case 'search':
      return { ...state, q: action.q, page: 0 };
    case 'filters':
      return {
        ...state,
        types: action.types,
        time: action.time,
        from: action.from,
        to: action.to,
        sizes: action.sizes,
        page: 0,
      };
    case 'sort':
      return { ...state, sort: action.sort, page: 0 };
    case 'pageSize':
      return { ...state, pageSize: action.pageSize, page: 0 };
    case 'page':
      return { ...state, page: action.page };
    case 'clear':
      return { ...initialMemoryQuery, sort: state.sort, pageSize: state.pageSize };
  }
}

function localDay(value: string, endOfDay: boolean): Date | null {
  const [year, month, day] = value.split('-').map(Number);
  if (year === undefined || month === undefined || day === undefined) {
    return null;
  }
  const date = endOfDay
    ? new Date(year, month - 1, day, 23, 59, 59, 999)
    : new Date(year, month - 1, day);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** The request params, or `range` when a custom range has neither date, a bad date or ends before it starts. */
export function toParams(state: MemoryQuery): { params: ListDriveMemoryParams } | { error: 'range' } {
  const params: ListDriveMemoryParams = {
    sort: state.sort,
    page: state.page,
    pageSize: state.pageSize,
  };
  const q = state.q.trim();
  if (q) {
    params.q = q;
  }
  if (state.types.length > 0) {
    params.type = [...state.types];
  }
  if (state.sizes.length > 0) {
    params.size = [...state.sizes];
  }
  if (state.time === 'custom') {
    const from = state.from ? localDay(state.from, false) : null;
    const to = state.to ? localDay(state.to, true) : null;
    if ((state.from && !from) || (state.to && !to) || (!from && !to) || (from && to && from > to)) {
      return { error: 'range' };
    }
    params.time = 'custom';
    if (from) {
      params.from = from.toISOString();
    }
    if (to) {
      params.to = to.toISOString();
    }
  } else if (state.time !== 'all') {
    params.time = state.time;
  }
  return { params };
}
