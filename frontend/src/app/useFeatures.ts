import { useQuery } from '@tanstack/react-query';
import { features, unwrap, type FeaturesResponse } from '@flow-cortex/api-client';

const ALL_OFF: FeaturesResponse = {
  emailConfirmation: false,
  phoneVerification: false,
  uploadContentCheck: false,
  runCancellation: false,
  credits: false,
};

/**
 * The backend's feature switches, fetched once per session (they change only on a backend
 * restart). Every feature reads off until they arrive and when the request fails, so a UI never
 * offers an action the backend may refuse.
 */
export function useFeatures(): FeaturesResponse {
  const { data } = useQuery({
    queryKey: ['features'],
    queryFn: () => unwrap(features()),
    staleTime: Infinity,
  });
  return data ?? ALL_OFF;
}
