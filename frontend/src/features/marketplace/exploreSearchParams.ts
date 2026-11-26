import { LABELLED_STEP_TYPES } from './stepTypeLabels';

/** The orders the catalogue route accepts, by their wire name. */
export const EXPLORE_SORTS = ['trending', 'popular', 'newest', 'topRated', 'name'] as const;
export type ExploreSort = (typeof EXPLORE_SORTS)[number];
export type ExploreTab = 'discover' | 'saved';

export const MAX_SEARCH_LENGTH = 100;
export const MAX_SEARCH_TERMS = 8;
export const MAX_STEP_TYPES = 10;

export interface ExploreState {
  readonly q: string;
  readonly types: readonly string[];
  readonly sort: ExploreSort;
  /** Whether the address names an order; the default order is written only when someone chose it. */
  readonly sortChosen: boolean;
  readonly tab: ExploreTab;
}

const isSort = (value: string | null): value is ExploreSort =>
  EXPLORE_SORTS.some((sort) => sort === value);

const TERM = /\S+/gu;

/** `text` cut after its last allowed term, so typing or pasting cannot reach a search the API refuses. */
export function limitTerms(text: string): string {
  const terms = [...text.matchAll(TERM)];
  const last = terms[MAX_SEARCH_TERMS - 1];
  if (terms.length <= MAX_SEARCH_TERMS || last === undefined) return text;
  return text.slice(0, last.index + last[0].length);
}

function searchOf(params: URLSearchParams): string {
  const q = (params.get('q') ?? '').trim();
  const tooLong = [...q.normalize('NFC')].length > MAX_SEARCH_LENGTH;
  const tooManyTerms = [...q.matchAll(TERM)].length > MAX_SEARCH_TERMS;
  return tooLong || tooManyTerms ? '' : q;
}

function typesOf(params: URLSearchParams): string[] {
  const types = params.getAll('type').map((type) => type.trim());
  const known = types.filter((type) => LABELLED_STEP_TYPES.includes(type));
  return [...new Set(known)].slice(0, MAX_STEP_TYPES);
}

/** The address read as state; every value that is not valid falls back to its default. */
export function parse(params: URLSearchParams): ExploreState {
  const sort = params.get('sort');
  return {
    q: searchOf(params),
    types: typesOf(params),
    sort: isSort(sort) ? sort : 'trending',
    sortChosen: isSort(sort),
    tab: params.get('tab') === 'saved' ? 'saved' : 'discover',
  };
}

/** The address for a state, leaving out whatever nobody chose. */
export function serialize(state: ExploreState): URLSearchParams {
  const params = new URLSearchParams();
  const q = state.q.trim();
  if (q !== '') params.set('q', q);
  for (const type of state.types) params.append('type', type);
  if (state.sortChosen) params.set('sort', state.sort);
  if (state.tab === 'saved') params.set('tab', 'saved');
  return params;
}

/** Whether a search, a pressed chip or a chosen order narrows the catalogue. */
export function hasFilters(state: ExploreState): boolean {
  return state.q.trim() !== '' || state.types.length > 0 || state.sortChosen;
}

const entriesOf = (params: URLSearchParams): string =>
  [...params.entries()]
    .map(([key, value]) => `${key}=${value}`)
    .sort()
    .join('&');

/** Whether the address already says exactly what the page reads from it, in any key order. */
export function isCanonical(params: URLSearchParams): boolean {
  return entriesOf(params) === entriesOf(serialize(parse(params)));
}
