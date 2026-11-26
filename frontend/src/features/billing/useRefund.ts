import {
  ApiError,
  authHeaders,
  cancelRefundRequest,
  getRefundEligibility,
  requestRefund,
  unwrap,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export const REASON_MIN = 10;
export const REASON_MAX = 2000;

const NOT_ELIGIBLE = 'refund_not_eligible';
const NOT_CANCELLABLE = 'refund_request_not_cancellable';

function refundKey(transactionId: string) {
  return ['billing', 'refund', transactionId] as const;
}

/** The problem `code` of a 409 the refund routes answer, else `undefined`. */
function conflictCode(error: unknown): string | undefined {
  return error instanceof ApiError && error.status === 409 ? error.problem?.code : undefined;
}

/** The member was refused after the block offered the right: the dialog closes and the verdict is re-read. */
export function isNotEligible(error: unknown): boolean {
  return conflictCode(error) === NOT_ELIGIBLE;
}

/** The request left `pending` before the cancel reached the server. */
export function isNotCancellable(error: unknown): boolean {
  return conflictCode(error) === NOT_CANCELLABLE;
}

/** The text of a 400 on the reason field, when the server refused the reason. */
export function reasonFieldError(error: unknown): string | undefined {
  return error instanceof ApiError && error.status === 400
    ? error.problem?.errors?.reason
    : undefined;
}

/** The right over one payment; a failed read leaves the block hidden, never the payment itself. */
export function useRefundEligibility(transactionId: string) {
  return useQuery({
    queryKey: refundKey(transactionId),
    queryFn: () => unwrap(getRefundEligibility(transactionId, authHeaders())),
  });
}

/** Every outcome re-reads the eligibility, so the block always shows what the server now says. */
function useRereadEligibility(transactionId: string) {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: refundKey(transactionId) });
}

export function useRequestRefund(transactionId: string) {
  const reread = useRereadEligibility(transactionId);
  return useMutation({
    mutationFn: (reason: string) => unwrap(requestRefund(transactionId, { reason }, authHeaders())),
    onSettled: reread,
  });
}

export function useCancelRefundRequest(transactionId: string) {
  const reread = useRereadEligibility(transactionId);
  return useMutation({
    mutationFn: () => unwrap(cancelRefundRequest(transactionId, authHeaders())),
    onSettled: reread,
  });
}
