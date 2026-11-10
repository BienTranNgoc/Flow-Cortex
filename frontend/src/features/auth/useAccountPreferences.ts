import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  get2 as getPreferences,
  patchPrivacy as patchPrivacyRequest,
  patchUi as patchUiRequest,
  setLocale as setLocaleRequest,
  unwrap,
  useAuthToken,
} from '@flow-cortex/api-client';
import type {
  JsonNode,
  PreferencesView,
  PrivacySettings,
  UiPreferences,
} from '@flow-cortex/api-client';
import type { Locale } from '@flow-cortex/i18n';

const preferencesKey = (token: string | null) => ['account-preferences', token] as const;

// The generated PATCH helpers type their body as the schema-less JsonNode the controller reads.
const asBody = (patch: UiPreferences | Partial<PrivacySettings>) => patch as JsonNode;

export function useAccountPreferencesQuery() {
  const token = useAuthToken();
  return useQuery({
    queryKey: preferencesKey(token),
    queryFn: () => unwrap(getPreferences(authHeaders())),
    enabled: Boolean(token),
    retry: false,
    // Applied once after sign-in; a refetch must not overwrite a choice made on this device since.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
  });
}

/**
 * Drops a load still in flight before a save starts, so its older answer cannot land after the
 * save, then writes the saved answer into the cached view (or reloads when nothing is cached).
 */
function useSavedPreferences<V, R>(
  save: (variables: V) => Promise<R>,
  merge: (view: PreferencesView, saved: R, variables: V) => PreferencesView,
) {
  const queryClient = useQueryClient();
  const key = preferencesKey(useAuthToken());
  return useMutation({
    mutationFn: save,
    onMutate: () => queryClient.cancelQueries({ queryKey: key }),
    onSuccess: (saved, variables) => {
      const cached = queryClient.getQueryData<PreferencesView>(key);
      if (cached) {
        queryClient.setQueryData(key, merge(cached, saved, variables));
      } else {
        void queryClient.invalidateQueries({ queryKey: key });
      }
    },
  });
}

/** Failures are swallowed: the device keeps the choice and the next successful load decides. */
export function useSaveLocale() {
  const { mutate } = useSavedPreferences(
    (locale: Locale) => unwrap(setLocaleRequest({ locale }, authHeaders())),
    (view, _saved, locale) => ({ ...view, locale }),
  );
  return { mutate: (locale: Locale) => mutate(locale, { onError: () => {} }) };
}

/** Failures are swallowed, as for the locale. */
export function useSaveUi() {
  const { mutate } = useSavedPreferences(
    (patch: UiPreferences) => unwrap(patchUiRequest(asBody(patch), authHeaders())),
    (view, saved) => ({ ...view, ui: saved }),
  );
  return { mutate: (patch: UiPreferences) => mutate(patch, { onError: () => {} }) };
}

/** Unlike the other two, a failed privacy save is surfaced: the caller reads `error`. */
export function useSavePrivacy() {
  return useSavedPreferences(
    (patch: Partial<PrivacySettings>) => unwrap(patchPrivacyRequest(asBody(patch), authHeaders())),
    (view, saved) => ({ ...view, privacy: saved }),
  );
}
