import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  changeCancel,
  changeStart,
  changeVerify,
  current,
  sendCode,
  unwrap,
  verify,
  type PhoneResponse,
  type SendCodeResponse,
} from '@flow-cortex/api-client';

const PHONE_KEY = ['phone'] as const;
const ME_KEY = ['me'] as const;

/** The signed-in member's phone number and whether it is verified. */
export function usePhoneQuery() {
  return useQuery({
    queryKey: PHONE_KEY,
    queryFn: () => unwrap(current(authHeaders())),
  });
}

/** Sends a one-time code to `phone`; a later send replaces the earlier code. */
export function useSendCodeMutation() {
  return useMutation({
    mutationFn: (phone: string) => unwrap(sendCode({ phone }, authHeaders())),
  });
}

/** Stores a phone status the server just returned and refreshes `/me`, whose gate flag depends on it. */
function useAcceptStatus() {
  const queryClient = useQueryClient();
  return (status: PhoneResponse) => {
    queryClient.setQueryData(PHONE_KEY, status);
    void queryClient.invalidateQueries({ queryKey: ME_KEY });
  };
}

/** Verifies the latest code; on success the answer becomes the cached phone status. */
export function useVerifyCodeMutation() {
  const accept = useAcceptStatus();
  return useMutation({
    mutationFn: (code: string) => unwrap(verify({ code }, authHeaders())),
    onSuccess: accept,
  });
}

/** Sends a code to `phone` for a number change; every call spends the one-use `stepUpProof`. */
export function useChangeStartMutation() {
  return useMutation({
    mutationFn: (request: { phone: string; stepUpProof: string }) =>
      unwrap(changeStart(request, authHeaders())) as Promise<SendCodeResponse>,
  });
}

/** Verifies the latest change code; on success the new number becomes the cached phone status. */
export function useChangeVerifyMutation() {
  const accept = useAcceptStatus();
  return useMutation({
    mutationFn: async (code: string) =>
      (await unwrap(changeVerify({ code }, authHeaders()))) as PhoneResponse,
    onSuccess: accept,
  });
}

/** Undoes the latest change inside its window; on success the restored number is the cached status. */
export function useChangeCancelMutation() {
  const accept = useAcceptStatus();
  return useMutation({
    mutationFn: async () => (await unwrap(changeCancel(authHeaders()))) as PhoneResponse,
    onSuccess: accept,
  });
}

/** Re-reads the phone status, for a refusal that shows the cached one is out of date. */
export function useRefreshPhone() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: PHONE_KEY });
}

// The coded OTP problems carry `code` and `retryAfterSeconds` as extension members, which the
// shared ProblemDetail type does not declare.
export function problemCode(error: unknown): string | undefined {
  const problem = error instanceof ApiError ? error.problem : undefined;
  return problem && 'code' in problem && typeof problem.code === 'string' ? problem.code : undefined;
}

export function retryAfterSeconds(error: unknown): number | undefined {
  const problem = error instanceof ApiError ? error.problem : undefined;
  return problem && 'retryAfterSeconds' in problem && typeof problem.retryAfterSeconds === 'number'
    ? problem.retryAfterSeconds
    : undefined;
}
