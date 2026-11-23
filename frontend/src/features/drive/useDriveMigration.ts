import { authHeaders, getDriveMigration, unwrap } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';

/** What the one-time move of earlier runs and results into Drive did for this member. */
export function useDriveMigration() {
  return useQuery({
    queryKey: ['drive', 'migration'],
    queryFn: () => unwrap(getDriveMigration(authHeaders())),
    retry: false,
  });
}
