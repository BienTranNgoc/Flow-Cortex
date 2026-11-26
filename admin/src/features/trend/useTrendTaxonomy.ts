import {
  authHeaders,
  createAdminTrendCategory,
  createAdminTrendTag,
  deleteAdminTrendCategory,
  deleteAdminTrendTag,
  listAdminTrendTaxonomy,
  reorderAdminTrendCategories,
  reorderAdminTrendTags,
  updateAdminTrendCategory,
  updateAdminTrendTag,
  type AdminTrendCategoryUpdateRequest,
  type AdminTrendTagUpdateRequest,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

export interface NamePair {
  nameVi: string;
  nameEn: string;
}

export type TaxonomyKind = 'category' | 'tag';

/** Longest name the API stores, per kind. */
export const NAME_LIMIT: Readonly<Record<TaxonomyKind, number>> = { category: 60, tag: 30 };

const TAXONOMY_KEY = ['admin', 'trend', 'taxonomy'] as const;

/** Both lists in one read: the server answers every category and tag in display order. */
export function useTaxonomyQuery() {
  return useQuery({
    queryKey: TAXONOMY_KEY,
    queryFn: () => unwrapSupport(listAdminTrendTaxonomy(authHeaders())),
  });
}

/** A refused write re-reads the lists too, so a record changed by another admin shows. */
function useTaxonomyWrite<V, R>(write: (variables: V) => Promise<R>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: write,
    onSettled: () => queryClient.invalidateQueries({ queryKey: TAXONOMY_KEY }),
  });
}

export function useCreateCategoryMutation() {
  return useTaxonomyWrite((names: NamePair) =>
    unwrapSupport(createAdminTrendCategory(names, authHeaders())),
  );
}

export function useUpdateCategoryMutation() {
  return useTaxonomyWrite(
    ({ key, changes }: { key: string; changes: AdminTrendCategoryUpdateRequest }) =>
      unwrapSupport(updateAdminTrendCategory(key, changes, authHeaders())),
  );
}

export function useReorderCategoriesMutation() {
  return useTaxonomyWrite((keys: string[]) =>
    unwrapSupport(reorderAdminTrendCategories({ keys }, authHeaders())),
  );
}

export function useDeleteCategoryMutation() {
  return useTaxonomyWrite((key: string) =>
    unwrapSupport(deleteAdminTrendCategory(key, authHeaders())),
  );
}

export function useCreateTagMutation() {
  return useTaxonomyWrite((names: NamePair) =>
    unwrapSupport(createAdminTrendTag(names, authHeaders())),
  );
}

export function useUpdateTagMutation() {
  return useTaxonomyWrite(({ id, changes }: { id: string; changes: AdminTrendTagUpdateRequest }) =>
    unwrapSupport(updateAdminTrendTag(id, changes, authHeaders())),
  );
}

export function useReorderTagsMutation() {
  return useTaxonomyWrite((ids: string[]) =>
    unwrapSupport(reorderAdminTrendTags({ ids }, authHeaders())),
  );
}

export function useDeleteTagMutation() {
  return useTaxonomyWrite((id: string) => unwrapSupport(deleteAdminTrendTag(id, authHeaders())));
}
