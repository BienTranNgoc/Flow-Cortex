import { authHeaders, listAdminFeatures } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

/** Every member feature switch in the member app's order, with the setting key of a runtime one. */
export function useAdminFeatures() {
  return useQuery({
    queryKey: ['admin', 'features'],
    queryFn: () => unwrapSupport(listAdminFeatures(authHeaders())),
  });
}
