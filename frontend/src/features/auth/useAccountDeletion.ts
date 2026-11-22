import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import {
  ApiError,
  authHeaders,
  cancelAccountDeletion as cancelRequest,
  getAccountDeletionEligibility as eligibilityRequest,
  getAccountDeletionStatus as statusRequest,
  requestAccountDeletion as submitRequest,
  unwrap,
} from '@flow-cortex/api-client';

const STATUS_KEY = ['accountDeletion', 'status'] as const;
const ELIGIBILITY_KEY = ['accountDeletion', 'eligibility'] as const;
const ME_KEY = ['me'] as const;

/** Whether deleting an account is switched on, the grace period, and the member's own request state. */
export function useAccountDeletionStatus() {
  return useQuery({
    queryKey: STATUS_KEY,
    queryFn: () => unwrap(statusRequest(authHeaders())),
  });
}

/**
 * The conditions that stop the member from requesting deletion right now. Read only while `open`
 * and never kept: the answer is dropped on close, so every opening waits for a fresh one instead
 * of showing the previous opening's.
 */
export function useDeletionEligibility(open: boolean) {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (!open) queryClient.removeQueries({ queryKey: ELIGIBILITY_KEY });
  }, [open, queryClient]);
  return useQuery({
    queryKey: ELIGIBILITY_KEY,
    queryFn: () => unwrap(eligibilityRequest(authHeaders())),
    enabled: open,
    gcTime: 0,
    staleTime: 0,
  });
}

/** Both mutations change what `/me` reports (the wall) and what the status says. */
function useRefreshAfterChange() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ME_KEY }),
      queryClient.invalidateQueries({ queryKey: STATUS_KEY }),
    ]);
}

/** Starts the grace period with the one-use step-up proof for `ACCOUNT_DELETION`. */
export function useRequestAccountDeletion() {
  const refresh = useRefreshAfterChange();
  return useMutation({
    mutationFn: (stepUpProof: string) => unwrap(submitRequest({ stepUpProof }, authHeaders())),
    onSuccess: refresh,
  });
}

/**
 * Cancels a pending deletion before its deadline. A refusal refreshes too: it means the server's
 * state differs from what the screen shows (deadline passed, nothing pending).
 */
export function useCancelAccountDeletion() {
  const refresh = useRefreshAfterChange();
  return useMutation({
    mutationFn: () => unwrap(cancelRequest(authHeaders())),
    onSettled: refresh,
  });
}

/** What a refused request or cancel means for the member, from its status and problem code. */
export type DeletionFailure =
  | { kind: 'blocked'; reasons: BlockReason[] }
  | { kind: 'staffAccount' }
  | { kind: 'stepUpRequired' }
  | { kind: 'unavailable' }
  | { kind: 'deadlinePassed' }
  | { kind: 'notPending' }
  | { kind: 'unknown' };

export function deletionFailure(error: unknown): DeletionFailure {
  if (!(error instanceof ApiError)) return { kind: 'unknown' };
  const code = (error.problem as { code?: unknown } | undefined)?.code;
  if (error.status === 409 && code === 'account.deletion.blocked') {
    return { kind: 'blocked', reasons: blockReasons(error) };
  }
  if (error.status === 409 && code === 'account.deletion.staffAccount') return { kind: 'staffAccount' };
  if (error.status === 409 && code === 'account.deletion.notPending') return { kind: 'notPending' };
  if (error.status === 403 && code === 'STEP_UP_REQUIRED') return { kind: 'stepUpRequired' };
  if (error.status === 422 && code === 'account.deletion.recoveryExpired') return { kind: 'deadlinePassed' };
  if (error.status === 503) return { kind: 'unavailable' };
  return { kind: 'unknown' };
}

/** One reason a deletion is refused: the condition key and its message in the caller's language. */
export interface BlockReason {
  key: string;
  message: string;
}

/** The `reasons` property a 409 `account.deletion.blocked` problem carries; empty when absent or malformed. */
export function blockReasons(error: unknown): BlockReason[] {
  const problem = error instanceof ApiError ? (error.problem as { reasons?: unknown } | undefined) : undefined;
  const reasons = problem?.reasons;
  if (!Array.isArray(reasons)) return [];
  return reasons.filter(
    (reason): reason is BlockReason =>
      typeof reason === 'object' &&
      reason !== null &&
      typeof (reason as BlockReason).key === 'string' &&
      typeof (reason as BlockReason).message === 'string',
  );
}
