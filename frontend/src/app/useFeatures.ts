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
  onboarding: false,
  chat: false,
};

/** The `/features` query itself, for a page that must tell "still loading" from "switched off". */
export function useFeaturesQuery() {
  return useQuery({
    queryKey: ['features'],
    queryFn: () => unwrap(features()),
    staleTime: Infinity,
  });
}

/**
 * The backend's feature switches, fetched once per session (they change only on a backend
 * restart). Every feature reads off until they arrive and when the request fails, so a UI never
 * offers an action the backend may refuse.
 */
export function useFeatures(): FeaturesResponse {
  return useFeaturesQuery().data ?? ALL_OFF;
}
