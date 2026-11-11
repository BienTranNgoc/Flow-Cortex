import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  create1,
  list1,
  revoke,
  unwrap,
  type ApiTokenView,
  type CreateApiTokenRequest,
  type CreatedApiToken,
} from '@flow-cortex/api-client';

export const API_TOKENS_KEY = ['api-tokens'] as const;

/** The member's unrevoked API tokens; asks nothing while the section is not offered to them. */
export function useApiTokensQuery(enabled: boolean) {
  return useQuery({
    queryKey: API_TOKENS_KEY,
    // `unwrap` throws for every status >= 400, so the generated error bodies never reach here.
    queryFn: async () => (await unwrap(list1(authHeaders()))) as ApiTokenView[],
    enabled,
  });
}

/** Creates a token; the answer carries the raw token once and is never cached. */
export function useCreateApiTokenMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: async (request: CreateApiTokenRequest) =>
      (await unwrap(create1(request, authHeaders()))) as CreatedApiToken,
    onSuccess: () => queryClient.invalidateQueries({ queryKey: API_TOKENS_KEY }),
  });
}

export function useRevokeApiTokenMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap(revoke(id, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: API_TOKENS_KEY }),
  });
}
