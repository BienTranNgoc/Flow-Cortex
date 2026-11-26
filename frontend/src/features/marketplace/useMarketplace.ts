import {
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
  type InfiniteData,
} from '@tanstack/react-query';
import {
  authHeaders,
  getMarketplaceFacets,
  getMarketplaceStats,
  getWorkflowCounts,
  listBookmarkedWorkflows,
  listMarketplaceWorkflows,
  listRecommendedWorkflows,
  listSimilarWorkflows,
  setWorkflowBookmark,
  unwrap,
} from '@flow-cortex/api-client';
import type {
  Item,
  ListMarketplaceWorkflowsParams,
  MarketplaceItem,
  MarketplaceItemsResponse,
  MarketplacePageResponse,
} from '@flow-cortex/api-client';
import type { ExploreSort } from './exploreSearchParams';
import { mergePages } from './mergePages';

export const MARKETPLACE_PAGE_SIZE = 20;
export const ROW_SIZE = 4;
export const SIMILAR_LIMIT = 8;

const MARKETPLACE_KEY = ['marketplace'] as const;
const COUNTS_KEY = ['workflows', 'counts'] as const;
const SAVED_KEY = [...MARKETPLACE_KEY, 'saved'] as const;

export function useMarketplaceStats() {
  return useQuery({
    queryKey: [...MARKETPLACE_KEY, 'stats'],
    queryFn: () => unwrap(getMarketplaceStats(authHeaders())),
  });
}

export function useMarketplaceFacets() {
  return useQuery({
    queryKey: [...MARKETPLACE_KEY, 'facets'],
    queryFn: () => unwrap(getMarketplaceFacets(authHeaders())),
  });
}

export function useWorkflowCounts() {
  return useQuery({
    queryKey: COUNTS_KEY,
    queryFn: () => unwrap(getWorkflowCounts(authHeaders())),
  });
}

export interface BrowseFilters {
  readonly q: string;
  readonly types: readonly string[];
  readonly sort: ExploreSort;
  readonly size?: number;
  readonly enabled?: boolean;
}

function browseParams(filters: BrowseFilters, page: number): ListMarketplaceWorkflowsParams {
  return {
    ...(filters.q === '' ? {} : { q: filters.q }),
    ...(filters.types.length === 0 ? {} : { stepType: [...filters.types] }),
    sort: filters.sort,
    page,
    size: filters.size ?? MARKETPLACE_PAGE_SIZE,
  };
}

/** The pages as one list without a repeated workflow, and the total the first page reported. */
function mergedPages(data: InfiniteData<MarketplacePageResponse, number>) {
  return {
    items: mergePages(data.pages),
    total: data.pages[0]?.meta.total ?? 0,
  };
}

/** The catalogue 20 at a time: `fetchNextPage` while `hasNextPage`. */
export function useMarketplaceBrowse(filters: BrowseFilters) {
  const { enabled = true, ...key } = filters;
  return useInfiniteQuery({
    queryKey: [...MARKETPLACE_KEY, 'browse', key],
    queryFn: ({ pageParam }) =>
      unwrap(listMarketplaceWorkflows(browseParams(filters, pageParam), authHeaders())),
    initialPageParam: 0,
    getNextPageParam: ({ meta }) =>
      (meta.page + 1) * meta.size < meta.total ? meta.page + 1 : undefined,
    select: mergedPages,
    enabled,
  });
}

export function useRecommended(limit: number, enabled = true) {
  return useQuery({
    queryKey: [...MARKETPLACE_KEY, 'recommended', limit],
    queryFn: () => unwrap(listRecommendedWorkflows({ limit }, authHeaders())),
    select: (response) => response.data,
    enabled,
  });
}

export function useSimilar(workflowId: string, limit: number = SIMILAR_LIMIT) {
  return useQuery({
    queryKey: [...MARKETPLACE_KEY, 'similar', workflowId, limit],
    queryFn: () => unwrap(listSimilarWorkflows(workflowId, { limit }, authHeaders())),
    select: (response): MarketplaceItem[] => response.data,
    enabled: Boolean(workflowId),
  });
}

/**
 * The saved list's own `meta` is typed with the cursor shape the contract generator picked for
 * the name `Meta`, but the server answers `{total, page, size}`.
 */
function savedTotal(meta: unknown): number | undefined {
  const total = (meta as { total?: unknown } | undefined)?.total;
  return typeof total === 'number' ? total : undefined;
}

function nextSavedPage(
  last: { data: Item[]; meta: unknown },
  pages: ReadonlyArray<{ data: Item[] }>,
): number | undefined {
  const total = savedTotal(last.meta);
  if (total === undefined) {
    return last.data.length === MARKETPLACE_PAGE_SIZE ? pages.length : undefined;
  }
  return pages.length * MARKETPLACE_PAGE_SIZE < total ? pages.length : undefined;
}

/** The member's saved workflows, newest saved first, 20 at a time. */
export function useSavedWorkflows(enabled = true) {
  return useInfiniteQuery({
    queryKey: SAVED_KEY,
    queryFn: ({ pageParam }) =>
      unwrap(
        listBookmarkedWorkflows({ page: pageParam, size: MARKETPLACE_PAGE_SIZE }, authHeaders()),
      ),
    initialPageParam: 0,
    getNextPageParam: nextSavedPage,
    select: (data) => ({
      items: mergePages(data.pages),
      total: savedTotal(data.pages[0]?.meta) ?? data.pages[0]?.data.length ?? 0,
    }),
    enabled,
  });
}

function flagged(items: MarketplaceItem[], id: string, bookmarked: boolean): MarketplaceItem[] {
  return items.map((item) => (item.workflow.id === id ? { ...item, bookmarked } : item));
}

/**
 * Saves or unsaves one workflow. The same workflow can show in several lists at once, so every
 * cached copy takes the answer; the member's counts and the saved list reload.
 */
export function useBookmarkToggle() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, bookmarked }: { id: string; bookmarked: boolean }) =>
      unwrap(setWorkflowBookmark(id, { bookmarked }, authHeaders())),
    onSuccess: (answer, { id }) => {
      queryClient.setQueriesData<InfiniteData<MarketplacePageResponse, number>>(
        { queryKey: [...MARKETPLACE_KEY, 'browse'] },
        (cached) =>
          cached && {
            ...cached,
            pages: cached.pages.map((page) => ({
              ...page,
              data: flagged(page.data, id, answer.bookmarked),
            })),
          },
      );
      for (const list of ['recommended', 'similar']) {
        queryClient.setQueriesData<MarketplaceItemsResponse>(
          { queryKey: [...MARKETPLACE_KEY, list] },
          (cached) => cached && { ...cached, data: flagged(cached.data, id, answer.bookmarked) },
        );
      }
      return Promise.all([
        queryClient.invalidateQueries({ queryKey: COUNTS_KEY }),
        queryClient.invalidateQueries({ queryKey: SAVED_KEY }),
      ]);
    },
  });
}
