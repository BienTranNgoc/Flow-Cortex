import {
  authHeaders,
  getVatRateTimeline,
  publishVatRate,
  type VatRateTimelineResponse,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

const KEY = ['admin', 'vat-rates'] as const;

export interface PublishRate {
  readonly rateBps: number;
  readonly sourceNote?: string;
}

export function useVatRates() {
  return useQuery({
    queryKey: KEY,
    queryFn: () => unwrapSupport(getVatRateTimeline(undefined, authHeaders())),
  });
}

/** A success replaces the cached timeline with the one the server answered. */
export function usePublishVatRate() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ rateBps, sourceNote }: PublishRate) =>
      unwrapSupport(
        publishVatRate(
          { rateBps, ...(sourceNote === undefined ? {} : { sourceNote }) },
          authHeaders(),
        ),
      ),
    onSuccess: ({ jurisdiction, category, rates }) => {
      queryClient.setQueryData<VatRateTimelineResponse>(KEY, { jurisdiction, category, rates });
    },
  });
}
