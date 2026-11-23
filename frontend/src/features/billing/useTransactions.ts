import {
  ApiError,
  authHeaders,
  getBillingTransaction,
  listBillingTransactions,
  unwrap,
  type TransactionDetailResponse,
} from '@flow-cortex/api-client';
import { keepPreviousData, useQuery } from '@tanstack/react-query';

export const TRANSACTIONS_PAGE_SIZE = 20;

export type TransactionLookup =
  | { readonly kind: 'found'; readonly transaction: TransactionDetailResponse }
  | { readonly kind: 'notFound' };

/** `page` counts from 0, as the API does; `settled` keeps only payments where money reached us. */
export function useTransactionList(page: number, settled: boolean) {
  return useQuery({
    queryKey: ['billing', 'transactions', { page, settled }],
    queryFn: () =>
      unwrap(
        listBillingTransactions(
          { page, size: TRANSACTIONS_PAGE_SIZE, ...(settled ? { settled } : {}) },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

/** A 404 is an answer ("not yours or not there"), so it resolves to `notFound` instead of an error. */
export function useTransaction(id: string) {
  return useQuery({
    queryKey: ['billing', 'transaction', id],
    queryFn: async (): Promise<TransactionLookup> => {
      try {
        const transaction = await unwrap(getBillingTransaction(id, authHeaders()));
        return { kind: 'found', transaction };
      } catch (error) {
        if (error instanceof ApiError && error.status === 404) return { kind: 'notFound' };
        throw error;
      }
    },
  });
}
