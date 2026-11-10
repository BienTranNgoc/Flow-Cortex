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

const PREFERENCES_MUTATION_KEY = ['notification-preferences', 'update'] as const;

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
    mutationKey: PREFERENCES_MUTATION_KEY,
    // Same scope = saves run one after another in click order, so an older request can't land last.
    scope: { id: 'notification-preferences' },
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
    // Only the last save of a burst refetches, so no intermediate server state overwrites a pending switch.
    onSettled: () =>
      queryClient.isMutating({ mutationKey: PREFERENCES_MUTATION_KEY }) === 1
        ? queryClient.invalidateQueries({ queryKey: PREFERENCES_KEY })
        : undefined,
  });
}
