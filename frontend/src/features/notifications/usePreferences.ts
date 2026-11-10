import {
  authHeaders,
  getNotificationPreferences,
  unwrap,
  updateNotificationPreferences,
  type PatchBody,
  type PreferencesView,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export const PREFERENCES_KEY = ['notifications', 'preferences'] as const;

export type PreferenceField = keyof Required<PreferencesView>;

export const PREFERENCE_FIELDS: readonly PreferenceField[] = ['inApp', 'careEmail', 'marketingEmail'];

export function usePreferencesQuery() {
  return useQuery({
    queryKey: PREFERENCES_KEY,
    queryFn: () => unwrap(getNotificationPreferences(authHeaders())),
  });
}

/** Saves one change at once: the switch moves first and returns when the save fails. */
export function useUpdatePreferences() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (patch: PatchBody) => unwrap(updateNotificationPreferences(patch, authHeaders())),
    onMutate: async (patch) => {
      await queryClient.cancelQueries({ queryKey: PREFERENCES_KEY });
      const previous = queryClient.getQueryData<PreferencesView>(PREFERENCES_KEY);
      queryClient.setQueryData<PreferencesView>(PREFERENCES_KEY, (current) => ({ ...current, ...patch }));
      return { previous };
    },
    onError: (_error, _patch, context) => {
      queryClient.setQueryData(PREFERENCES_KEY, context?.previous);
    },
    onSettled: () => queryClient.invalidateQueries({ queryKey: PREFERENCES_KEY }),
  });
}
