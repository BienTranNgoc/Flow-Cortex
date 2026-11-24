import { useQuery } from '@tanstack/react-query';
import { features, unwrap, type FeaturesResponse } from '@flow-cortex/api-client';

const ALL_OFF: FeaturesResponse = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
  passwordReset: false,
  supportWidget: false,
  supportAssistant: false,
  personalAccessTokens: false,
  onboarding: false,
  chat: false,
  drive: false,
};

/**
 * The `/features` query itself, for a page that must tell "still loading" from "switched off".
 * An answer is re-read once it is 30 s old, on focus or mount; a failed re-read keeps the last one.
 */
export function useFeaturesQuery() {
  return useQuery({
    queryKey: ['features'],
    queryFn: () => unwrap(features()),
    staleTime: 30_000,
  });
}

/**
 * The backend's feature switches: an admin can flip some at runtime, so an answer is re-read once
 * it is 30 s old, on focus or mount. Every feature reads off until the first answer arrives and
 * when the first request fails, so a UI never offers an action the backend may refuse.
 */
export function useFeatures(): FeaturesResponse {
  return useFeaturesQuery().data ?? ALL_OFF;
}
