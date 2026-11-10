import {
  authHeaders,
  createAdminAiModel,
  deleteAdminAiModel,
  listAdminAiModels,
  listAdminAiProviderModels,
  listAdminAiProviders,
  replaceAdminAiModel,
  testAdminAiModel,
  testAdminAiProvider,
  unwrap,
  type AiModelRequest,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export const MODELS_PAGE_SIZE = 50;

const MODELS_KEY = ['admin', 'ai', 'models'] as const;
const PROVIDERS_KEY = ['admin', 'ai', 'providers'] as const;

type SuccessData<T> = Extract<T, { status: 200 | 201 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
async function unwrapSuccess<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

/** One page of registered models, inactive ones included; the previous page stays while the next loads. */
export function useAiModels(page: number) {
  return useQuery({
    queryKey: [...MODELS_KEY, page],
    queryFn: () =>
      unwrapSuccess(listAdminAiModels({ page, size: MODELS_PAGE_SIZE }, authHeaders())),
    placeholderData: keepPreviousData,
  });
}

/** Every registered provider by category, with its configuration state and model slots. */
export function useAiProviders() {
  return useQuery({
    queryKey: PROVIDERS_KEY,
    queryFn: () => unwrapSuccess(listAdminAiProviders(authHeaders())),
  });
}

/** The provider's live model list; runs only while the add form is open for a known provider. */
export function useVendorModels(providerKey: string, enabled: boolean) {
  return useQuery({
    queryKey: [...PROVIDERS_KEY, providerKey, 'models'],
    queryFn: () =>
      unwrapSuccess(listAdminAiProviderModels(encodeURIComponent(providerKey), authHeaders())),
    enabled: enabled && providerKey !== '',
    staleTime: 60 * 1000,
  });
}

function useRefreshModels() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: MODELS_KEY });
}

export function useCreateModelMutation() {
  const refresh = useRefreshModels();
  return useMutation({
    mutationFn: (body: AiModelRequest) => unwrapSuccess(createAdminAiModel(body, authHeaders())),
    onSuccess: refresh,
  });
}

export function useReplaceModelMutation() {
  const refresh = useRefreshModels();
  return useMutation({
    mutationFn: (body: AiModelRequest) =>
      unwrapSuccess(replaceAdminAiModel(encodeURIComponent(body.modelKey), body, authHeaders())),
    onSuccess: refresh,
  });
}

export function useDeleteModelMutation() {
  const refresh = useRefreshModels();
  return useMutation({
    mutationFn: (modelKey: string) =>
      unwrapSuccess(deleteAdminAiModel(encodeURIComponent(modelKey), authHeaders())),
    onSuccess: refresh,
  });
}

/** A model test spends tokens, so it is a mutation the admin starts, never a query. */
export function useTestModelMutation() {
  return useMutation({
    mutationFn: (modelKey: string) =>
      unwrapSuccess(testAdminAiModel(encodeURIComponent(modelKey), authHeaders())),
  });
}

export function useTestProviderMutation() {
  return useMutation({
    mutationFn: (providerKey: string) =>
      unwrapSuccess(testAdminAiProvider(encodeURIComponent(providerKey), authHeaders())),
  });
}
