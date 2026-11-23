import { ListUsersSort, ListUsersState } from '@flow-cortex/api-client';

export type UsersState = '' | ListUsersState;
export type UsersSort = ListUsersSort;

export interface UsersParams {
  q: string;
  role: string;
  state: UsersState;
  sort: UsersSort;
  page: number;
}

export const DEFAULT_USERS_PARAMS: UsersParams = {
  q: '',
  role: '',
  state: '',
  sort: 'newest',
  page: 0,
};

export const MAX_SEARCH_LENGTH = 100;
const MAX_PAGE = 21474836;
const ROLE_CODE_FORMAT = /^[A-Z][A-Z0-9_]{0,19}$/;
const PAGE_FORMAT = /^\d{1,8}$/;

export const USER_STATES: readonly ListUsersState[] = Object.values(ListUsersState);
export const USER_SORTS: readonly ListUsersSort[] = Object.values(ListUsersSort);

function oneOf<T extends string>(values: readonly T[], raw: string | null): T | undefined {
  return values.find((value) => value === raw);
}

/** Reads the list view from a URL; any invalid value falls back to its default so no bad value reaches the API. */
export function parseUsersParams(search: URLSearchParams): UsersParams {
  const role = search.get('role') ?? '';
  const page = search.get('page') ?? '';
  const parsedPage = PAGE_FORMAT.test(page) ? Number(page) : 0;
  return {
    q: (search.get('q') ?? '').trim().slice(0, MAX_SEARCH_LENGTH).trim(),
    role: ROLE_CODE_FORMAT.test(role) ? role : DEFAULT_USERS_PARAMS.role,
    state: oneOf(USER_STATES, search.get('state')) ?? DEFAULT_USERS_PARAMS.state,
    sort: oneOf(USER_SORTS, search.get('sort')) ?? DEFAULT_USERS_PARAMS.sort,
    page: parsedPage <= MAX_PAGE ? parsedPage : DEFAULT_USERS_PARAMS.page,
  };
}

/** The URL form of a list view; values equal to their default are left out. */
export function toSearchParams(params: UsersParams): URLSearchParams {
  const search = new URLSearchParams();
  if (params.q) search.set('q', params.q);
  if (params.role) search.set('role', params.role);
  if (params.state) search.set('state', params.state);
  if (params.sort !== DEFAULT_USERS_PARAMS.sort) search.set('sort', params.sort);
  if (params.page > 0) search.set('page', String(params.page));
  return search;
}
