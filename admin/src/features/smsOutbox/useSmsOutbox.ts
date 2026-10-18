import { useQuery } from '@tanstack/react-query';
import { authHeaders, listAdminSmsOutbox, unwrap } from '@flow-cortex/api-client';

export const OUTBOX_LIMIT = 50;

/** The latest development SMS messages, newest first. */
export function useSmsOutbox() {
  return useQuery({
    queryKey: ['admin', 'sms-outbox'],
    queryFn: () => unwrap(listAdminSmsOutbox({ limit: OUTBOX_LIMIT }, authHeaders())),
  });
}
