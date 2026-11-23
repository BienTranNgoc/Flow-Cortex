import { authHeaders, claimFirstResult } from '@flow-cortex/api-client';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect } from 'react';
import { ONBOARDING_STATUS_KEY } from './useOnboarding';

// One attempt per page session; a failure waits for the next load rather than retrying.
let claimAttempted = false;

export function resetFirstResultClaim() {
  claimAttempted = false;
}

/**
 * Reports the member's first completed run once it is pending. Silent by design: the claim
 * has no member-facing outcome, so every failure is dropped and the next load asks again.
 */
export function useFirstResultClaim(firstResult: string | undefined) {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (firstResult !== 'pending' || claimAttempted) return;
    claimAttempted = true;
    claimFirstResult(authHeaders())
      .then((answer) => {
        if (answer.status === 200) {
          return queryClient.invalidateQueries({ queryKey: ONBOARDING_STATUS_KEY });
        }
      })
      .catch(() => undefined);
  }, [firstResult, queryClient]);
}

/** Asks for a fresh status when the run on this page completes, so a pending report is claimed. */
export function useRefreshOnboardingOnCompletion(runStatus: string | undefined) {
  const queryClient = useQueryClient();
  useEffect(() => {
    if (runStatus === 'completed') {
      void queryClient.invalidateQueries({ queryKey: ONBOARDING_STATUS_KEY });
    }
  }, [runStatus, queryClient]);
}
