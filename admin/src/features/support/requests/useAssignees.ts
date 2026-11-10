import { authHeaders, listAdminAssignees, unwrap } from '@flow-cortex/api-client';
import { useQuery } from '@tanstack/react-query';

/** Staff who can take a support request; read only for callers who may assign. */
export function useAssignees(enabled: boolean) {
  return useQuery({
    queryKey: ['admin', 'assignees', 'support.ticket.reply'],
    queryFn: () => unwrap(listAdminAssignees({ for: 'support.ticket.reply' }, authHeaders())),
    enabled,
    staleTime: 60_000,
  });
}
