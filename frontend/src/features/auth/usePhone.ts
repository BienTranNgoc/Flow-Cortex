import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  current,
  sendCode,
  unwrap,
  verify,
  type PhoneResponse,
} from '@flow-cortex/api-client';

const PHONE_KEY = ['phone'] as const;

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

/** Verifies the latest code; on success the answer becomes the cached phone status. */
export function useVerifyCodeMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => unwrap(verify({ code }, authHeaders())),
    onSuccess: (status: PhoneResponse) => {
      queryClient.setQueryData(PHONE_KEY, status);
    },
  });
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
