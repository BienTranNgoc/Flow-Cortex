import {
  ApiError,
  authHeaders,
  listAdminDeletionBlockConditions,
  updateAdminDeletionBlockCondition,
  type DeletionBlockConditionUpdate,
  type DeletionBlockConditionView,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

const CONDITIONS_KEY = ['admin', 'account-deletion', 'block-conditions'] as const;

export function useDeletionConditions() {
  return useQuery({
    queryKey: CONDITIONS_KEY,
    queryFn: () => unwrapSupport(listAdminDeletionBlockConditions(authHeaders())),
  });
}

/**
 * Writes the answered row into the cached list, so the row never waits for a refetch; any failure
 * other than a refused value (400) reloads the list, since the row may no longer exist or be stale.
 */
export function useUpdateDeletionCondition(key: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (update: DeletionBlockConditionUpdate) =>
      unwrapSupport(updateAdminDeletionBlockCondition(key, update, authHeaders())),
    onSuccess: (view: DeletionBlockConditionView) => {
      queryClient.setQueryData<DeletionBlockConditionView[]>(CONDITIONS_KEY, (list) =>
        list?.map((item) => (item.key === view.key ? view : item)),
      );
    },
    onError: (error: unknown) => {
      if (!(error instanceof ApiError && error.status === 400)) {
        void queryClient.invalidateQueries({ queryKey: CONDITIONS_KEY, exact: true });
      }
    },
  });
}
