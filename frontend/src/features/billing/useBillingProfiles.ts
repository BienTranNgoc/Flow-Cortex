import {
  ApiError,
  authHeaders,
  createBillingProfile,
  deleteBillingProfile,
  listBillingProfiles,
  setDefaultBillingProfile,
  unwrap,
  updateBillingProfile,
  type BillingProfileRequest,
  type BillingProfileResponse,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

const PROFILES_KEY = ['billing', 'profiles'] as const;

export type ProfileField =
  'type' | 'legalName' | 'taxCode' | 'personalIdNumber' | 'address' | 'email';

/** The server's per-field reasons of a refused profile (422), in the request's language. */
export function fieldErrorsOf(error: unknown): Partial<Record<ProfileField, string>> | undefined {
  if (!(error instanceof ApiError) || error.status !== 422) return undefined;
  return error.problem?.errors as Partial<Record<ProfileField, string>> | undefined;
}

/** How many profiles a member may keep; the server refuses the next one. */
export const MAX_PROFILES = 20;

const LIMIT_REACHED_CODE = 'billing_profile_limit_reached';

/** The member already holds {@link MAX_PROFILES} profiles, so a create is refused (422 with no field errors). */
export function isLimitReached(error: unknown): error is ApiError {
  return error instanceof ApiError && error.status === 422 && error.problem?.code === LIMIT_REACHED_CODE;
}

/** The profile is gone, or never was the member's: the list is re-read instead of showing a stale card. */
export function isGone(error: unknown): boolean {
  return error instanceof ApiError && error.status === 404;
}

export function useBillingProfiles() {
  return useQuery({
    queryKey: PROFILES_KEY,
    queryFn: () => unwrap(listBillingProfiles(authHeaders())),
  });
}

export interface SaveProfile {
  /** Absent creates a profile; present edits it. */
  readonly id?: string;
  readonly body: BillingProfileRequest;
}

/** Every mutation re-reads the list whatever the outcome, so a refusal or a 404 never leaves a stale card. */
function useRefreshingMutation<TInput, TOutput>(run: (input: TInput) => Promise<TOutput>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: run,
    onSettled: () => queryClient.invalidateQueries({ queryKey: PROFILES_KEY }),
  });
}

export function useSaveBillingProfile() {
  return useRefreshingMutation(
    ({ id, body }: SaveProfile): Promise<BillingProfileResponse> =>
      id === undefined
        ? unwrap(createBillingProfile(body, authHeaders()))
        : unwrap(updateBillingProfile(id, body, authHeaders())),
  );
}

export function useSetDefaultBillingProfile() {
  return useRefreshingMutation((id: string) => unwrap(setDefaultBillingProfile(id, authHeaders())));
}

export function useDeleteBillingProfile() {
  return useRefreshingMutation((id: string) => unwrap(deleteBillingProfile(id, authHeaders())));
}
