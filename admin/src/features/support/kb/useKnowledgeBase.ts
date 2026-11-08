import {
  archiveAdminSupportKbArticle,
  authHeaders,
  createAdminSupportKbArticle,
  createAdminSupportKbGroup,
  deleteAdminSupportKbGroup,
  getAdminSupportKbArticle,
  listAdminSupportKbArticles,
  listAdminSupportKbGroups,
  publishAdminSupportKbArticle,
  renameAdminSupportKbGroup,
  reorderAdminSupportKbGroups,
  updateAdminSupportKbArticle,
  type AdminSupportKbArticle,
  type AdminSupportKbArticleRequest,
  type AdminSupportKbArticleSummaryStatus,
  type AdminSupportKbGroup,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../unwrapSupport';

const KB_KEY = ['admin', 'support', 'kb'] as const;
const ARTICLES_KEY = [...KB_KEY, 'articles'] as const;
const GROUPS_KEY = [...KB_KEY, 'groups'] as const;

const articleKey = (id: string) => [...ARTICLES_KEY, id] as const;

/** Every article as one page, filtered here: the server returns them all, newest edit first. */
export function useArticlesQuery(status: AdminSupportKbArticleSummaryStatus | '') {
  return useQuery({
    queryKey: ARTICLES_KEY,
    queryFn: () => unwrapSupport(listAdminSupportKbArticles(authHeaders())),
    select: (list) => {
      const items = status ? list.items.filter((item) => item.status === status) : list.items;
      return {
        items,
        page: 0,
        size: Math.max(1, items.length),
        total: items.length,
        publishedFeaturedCount: list.publishedFeaturedCount,
      };
    },
  });
}

export function useArticleQuery(id: string | undefined) {
  return useQuery({
    queryKey: articleKey(id ?? ''),
    queryFn: () => unwrapSupport(getAdminSupportKbArticle(id ?? '', authHeaders())),
    enabled: id !== undefined,
  });
}

export function useGroupsQuery() {
  return useQuery({
    queryKey: GROUPS_KEY,
    queryFn: () => unwrapSupport(listAdminSupportKbGroups(authHeaders())),
  });
}

/** Stores the article the server answered and refreshes every list it appears in. */
function useArticleWrite<V>(write: (variables: V) => Promise<AdminSupportKbArticle>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSuccess: (article) => {
      queryClient.setQueryData(articleKey(article.id), article);
      queryClient.invalidateQueries({ queryKey: ARTICLES_KEY, exact: true });
      queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
    },
  });
}

export function useSaveArticleMutation(id: string | undefined) {
  return useArticleWrite((draft: AdminSupportKbArticleRequest) =>
    id === undefined
      ? unwrapSupport(createAdminSupportKbArticle(draft, authHeaders()))
      : unwrapSupport(updateAdminSupportKbArticle(id, draft, authHeaders())),
  );
}

export function useArticleStatusMutation(id: string) {
  return useArticleWrite((action: 'publish' | 'archive') =>
    action === 'publish'
      ? unwrapSupport(publishAdminSupportKbArticle(id, authHeaders()))
      : unwrapSupport(archiveAdminSupportKbArticle(id, authHeaders())),
  );
}

/** Re-reads the article after a refused write, so a status changed elsewhere shows. */
export function useRefreshArticle() {
  const queryClient = useQueryClient();
  return (id: string) => queryClient.invalidateQueries({ queryKey: articleKey(id) });
}

export function useRefreshGroups() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
}

/** A refused write re-reads the groups, so a list changed by another admin shows. */
function useGroupWrite<V, R>(
  write: (variables: V) => Promise<R>,
  store?: (result: R) => AdminSupportKbGroup[],
) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSuccess: (result) => {
      if (store) {
        queryClient.setQueryData(GROUPS_KEY, store(result));
      } else {
        queryClient.invalidateQueries({ queryKey: GROUPS_KEY });
      }
      queryClient.invalidateQueries({ queryKey: ARTICLES_KEY, exact: true });
    },
    onError: () => queryClient.invalidateQueries({ queryKey: GROUPS_KEY }),
  });
}

export function useCreateGroupMutation() {
  return useGroupWrite((name: string) =>
    unwrapSupport(createAdminSupportKbGroup({ name }, authHeaders())),
  );
}

export function useRenameGroupMutation() {
  return useGroupWrite(({ id, name }: { id: string; name: string }) =>
    unwrapSupport(renameAdminSupportKbGroup(id, { name }, authHeaders())),
  );
}

export function useDeleteGroupMutation() {
  return useGroupWrite((id: string) => unwrapSupport(deleteAdminSupportKbGroup(id, authHeaders())));
}

export function useReorderGroupsMutation() {
  return useGroupWrite(
    (ids: string[]) => unwrapSupport(reorderAdminSupportKbGroups({ ids }, authHeaders())),
    (groups) => groups,
  );
}
