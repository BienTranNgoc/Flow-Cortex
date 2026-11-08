import { useQuery } from '@tanstack/react-query';
import {
  ApiError,
  getSupportKbArticle,
  getSupportSettings,
  listSupportKbFeatured,
  listSupportKbGroups,
  searchSupportKb,
  unwrap,
} from '@flow-cortex/api-client';

/** Search terms shorter than this (in code points, after trimming) send nothing. */
export const MIN_SEARCH_LENGTH = 2;
const HOME_ARTICLES = 3;

export function searchTerm(raw: string): string | null {
  const term = raw.trim();
  return [...term].length >= MIN_SEARCH_LENGTH ? term : null;
}

type SuccessData<T> = Extract<T, { status: 200 | 201 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
export async function unwrapSupport<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

const PROBLEM_TYPE_BASE = 'https://flow-cortex.dev/problems/';

/**
 * The `code` of a problem response, else the last segment of a Flow-Cortex problem `type`
 * (a bean-validation 400 carries no `code`); `undefined` for any other failure.
 */
export function supportProblemCode(error: unknown): string | undefined {
  if (!(error instanceof ApiError) || !error.problem) return undefined;
  const { code, type } = error.problem as { code?: unknown; type?: unknown };
  if (typeof code === 'string') return code;
  if (typeof type === 'string' && type.startsWith(PROBLEM_TYPE_BASE)) {
    const segment = type.slice(PROBLEM_TYPE_BASE.length);
    return segment && !segment.includes('/') ? segment : undefined;
  }
  return undefined;
}

// The KB and settings routes answer anonymously; no bearer is sent so an expired member
// token cannot turn a public read into a 401 that signs the member out.

export function useSupportSettings() {
  return useQuery({
    queryKey: ['support', 'settings'],
    queryFn: () => unwrapSupport(getSupportSettings()),
    staleTime: 60_000,
  });
}

/** The configured contact address, or `null` when none is set. */
export function useSupportContact(): string | null {
  const email = useSupportSettings().data?.contactEmail?.trim();
  return email ? email : null;
}

export function useFeaturedArticles() {
  return useQuery({
    queryKey: ['support', 'kb', 'featured'],
    queryFn: () => unwrapSupport(listSupportKbFeatured()),
    select: (articles) =>
      [...articles].sort((a, b) => a.position - b.position).slice(0, HOME_ARTICLES),
  });
}

export function useKbGroups() {
  return useQuery({
    queryKey: ['support', 'kb', 'groups'],
    queryFn: () => unwrapSupport(listSupportKbGroups()),
    select: (groups) =>
      groups.filter((group) => group.articles.length > 0).sort((a, b) => a.position - b.position),
  });
}

export function useKbSearch(term: string | null) {
  return useQuery({
    queryKey: ['support', 'kb', 'search', term],
    queryFn: () => unwrapSupport(searchSupportKb({ q: term ?? '' })),
    enabled: term !== null,
  });
}

export function useKbArticle(slug: string) {
  return useQuery({
    queryKey: ['support', 'kb', 'article', slug],
    queryFn: () => unwrapSupport(getSupportKbArticle(encodeURIComponent(slug))),
  });
}
