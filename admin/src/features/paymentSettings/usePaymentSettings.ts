import {
  ApiError,
  authHeaders,
  listPaymentSettingHistory,
  listPaymentSettings,
  updatePaymentSetting,
  type PaymentSettingView,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

export const HISTORY_PAGE_SIZE = 25;

const LIST_KEY = ['admin', 'payment-settings'] as const;
const HISTORY_KEY = [...LIST_KEY, 'history'] as const;

export interface HistoryParams {
  readonly key: string;
  readonly page: number;
}

export interface PaymentSettingChange {
  readonly value: string | boolean | number;
  readonly reason?: string;
}

export function usePaymentSettingsList() {
  return useQuery({
    queryKey: LIST_KEY,
    queryFn: () => unwrapSupport(listPaymentSettings(authHeaders())),
  });
}

/** One page of change records, newest first; an empty `key` reads every payment setting. */
export function usePaymentSettingHistory({ key, page }: HistoryParams, enabled: boolean) {
  return useQuery({
    queryKey: [...HISTORY_KEY, { key, page, size: HISTORY_PAGE_SIZE }],
    queryFn: () =>
      unwrapSupport(
        listPaymentSettingHistory(
          { page, size: HISTORY_PAGE_SIZE, ...(key ? { key } : {}) },
          authHeaders(),
        ),
      ),
    enabled,
    placeholderData: keepPreviousData,
  });
}

/**
 * A success replaces the setting's entry with the answered view and drops the cached history pages;
 * a failure leaves the list as the server last answered it.
 */
export function useUpdatePaymentSetting(key: string) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ value, reason }: PaymentSettingChange) =>
      unwrapSupport(
        updatePaymentSetting(
          key,
          { value, ...(reason === undefined ? {} : { reason }) },
          authHeaders(),
        ),
      ),
    onSuccess: (view: PaymentSettingView) => {
      queryClient.setQueryData<PaymentSettingView[]>(LIST_KEY, (list) =>
        list?.map((item) => (item.key === view.key ? view : item)),
      );
      void queryClient.invalidateQueries({ queryKey: HISTORY_KEY });
    },
  });
}

/** The server's localized explanation of a refused request, when it sent one. */
export function problemDetail(error: unknown): string | undefined {
  return error instanceof ApiError ? error.problem?.detail : undefined;
}
