import { useMutation, useQuery } from '@tanstack/react-query';
import { ApiError, unsubscribeEmailLink, unwrap, viewEmailLink } from '@flow-cortex/api-client';
import type { EmailLinkUnsubscribed, EmailLinkView } from '@flow-cortex/api-client';

/** How a refused or failed link call reads to the page: a dead link, a throttled one, anything else. */
export type EmailLinkFailure = 'invalid' | 'rateLimited' | 'error';

export function emailLinkFailureFrom(error: unknown): EmailLinkFailure {
  if (error instanceof ApiError) {
    if (error.status === 404) return 'invalid';
    if (error.status === 429) return 'rateLimited';
  }
  return 'error';
}

/**
 * Reads what an unsubscribe link points at without changing anything. Every call spends the link's
 * rate-limit bucket, so nothing refetches on its own: no retry, no refocus, no reconnect.
 */
export function useEmailLinkQuery(token: string) {
  return useQuery({
    queryKey: ['email-link', token],
    // unwrap throws on the 404 and 429 arms of the generated union, so only the 200 body reaches here.
    queryFn: async () => (await unwrap(viewEmailLink(token))) as EmailLinkView,
    enabled: token !== '',
    retry: false,
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/** Presses the link's button; `src` is the catalog key the mail carried, which the server validates. */
export function useUnsubscribeEmailLinkMutation(token: string, src?: string) {
  return useMutation({
    mutationFn: async () =>
      (await unwrap(
        unsubscribeEmailLink(token, src ? { src } : undefined),
      )) as EmailLinkUnsubscribed,
  });
}
