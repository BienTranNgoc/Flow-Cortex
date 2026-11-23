import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  createApiToken,
  listApiTokens,
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
    queryFn: async () => (await unwrap(listApiTokens(authHeaders()))) as ApiTokenView[],
    enabled,
  });
}

/**
 * Creates a token; the answer carries the raw token once. `gcTime: 0` drops the finished mutation
 * from the cache as soon as the caller resets it, instead of holding the token for the default gc time.
 */
export function useCreateApiTokenMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    gcTime: 0,
    mutationFn: async (request: CreateApiTokenRequest) =>
      (await unwrap(createApiToken(request, authHeaders()))) as CreatedApiToken,
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
