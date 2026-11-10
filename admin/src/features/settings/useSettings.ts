import {
  ApiError,
  authHeaders,
  listAdminSettingHistory,
  listAdminSettings,
  resetAdminSetting,
  updateAdminSetting,
  type AdminSetting,
  type AdminSettingList,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { unwrapSupport } from '../support/unwrapSupport';

const SETTINGS_KEY = ['admin', 'settings'] as const;
export const HISTORY_PAGE_SIZE = 10;

export function useSettingsList() {
  return useQuery({
    queryKey: SETTINGS_KEY,
    queryFn: () => unwrapSupport(listAdminSettings(authHeaders())),
  });
}

/**
 * Writes the answered view of one key into the cached list, so the page never waits for a refetch;
 * a failure other than a refused value (422) reloads the list, since the page may be stale.
 */
function useWriteOutcome() {
  const queryClient = useQueryClient();
  return {
    onSuccess: (view: AdminSetting) => {
      queryClient.setQueryData<AdminSettingList>(
        SETTINGS_KEY,
        (list) => list && { items: list.items.map((item) => (item.key === view.key ? view : item)) },
      );
      void queryClient.invalidateQueries({ queryKey: [...SETTINGS_KEY, view.key, 'history'] });
    },
    onError: (error: unknown) => {
      if (!(error instanceof ApiError && error.status === 422)) {
        void queryClient.invalidateQueries({ queryKey: SETTINGS_KEY, exact: true });
      }
    },
  };
}

export function useUpdateSetting(key: string) {
  return useMutation({
    mutationFn: (value: boolean | number) =>
      unwrapSupport(updateAdminSetting(key, { value }, authHeaders())),
    ...useWriteOutcome(),
  });
}

export function useResetSetting(key: string) {
  return useMutation({
    mutationFn: () => unwrapSupport(resetAdminSetting(key, authHeaders())),
    ...useWriteOutcome(),
  });
}

/** One page of a key's history, fetched only while its disclosure is open. */
export function useSettingHistory(key: string, page: number, enabled: boolean) {
  return useQuery({
    queryKey: [...SETTINGS_KEY, key, 'history', page],
    queryFn: () =>
      unwrapSupport(listAdminSettingHistory(key, { page, size: HISTORY_PAGE_SIZE }, authHeaders())),
    enabled,
    placeholderData: keepPreviousData,
  });
}
