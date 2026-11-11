import { useQuery } from '@tanstack/react-query';
import { authHeaders, getAdminPhoneVerificationStatus, unwrap } from '@flow-cortex/api-client';

/** How the phone verification flow is switched and wired in the running backend. */
export function usePhoneVerificationStatus() {
  return useQuery({
    queryKey: ['admin', 'phone-verification', 'status'],
    queryFn: () => unwrap(getAdminPhoneVerificationStatus(authHeaders())),
  });
}
