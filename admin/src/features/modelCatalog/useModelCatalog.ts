import {
  authHeaders,
  createAdminAiCatalogModel,
  deleteAdminAiCatalogModel,
  getAdminAiCapability,
  listAdminAiCapabilities,
  listAdminAiCatalogModels,
  listAdminAiModelDescriptors,
  replaceAdminAiCapability,
  replaceAdminAiCatalogModel,
  unwrap,
  type CatalogCapabilityRequest,
  type CatalogModelCreateRequest,
  type CatalogModelReplaceRequest,
} from '@flow-cortex/api-client';
import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';

export const CATALOG_MODELS_PAGE_SIZE = 50;

const CAPABILITIES_KEY = ['admin', 'ai', 'capabilities'] as const;
const DESCRIPTORS_KEY = ['admin', 'ai', 'model-descriptors'] as const;

type SuccessData<T> = Extract<T, { status: 200 | 201 | 204 }> extends { data: infer D } ? D : never;

/** `unwrap` narrowed to the success body: it throws for every status >= 400. */
async function unwrapSuccess<T extends { status: number; data: unknown }>(
  request: Promise<T>,
): Promise<SuccessData<T>> {
  return (await unwrap(request)) as SuccessData<T>;
}

/** Every catalogued capability with its model counts, plus the registered types that have no row. */
export function useCapabilities() {
  return useQuery({
    queryKey: CAPABILITIES_KEY,
    queryFn: () => unwrapSuccess(listAdminAiCapabilities(authHeaders())),
  });
}

export function useCapability(capability: string) {
  return useQuery({
    queryKey: [...CAPABILITIES_KEY, capability],
    queryFn: () => unwrapSuccess(getAdminAiCapability(capability, authHeaders())),
  });
}

/** One page of a capability's model rows; the previous page stays while the next loads. */
export function useCapabilityModels(capability: string, page: number) {
  return useQuery({
    queryKey: [...CAPABILITIES_KEY, capability, 'models', page],
    queryFn: () =>
      unwrapSuccess(
        listAdminAiCatalogModels(
          capability,
          { page, size: CATALOG_MODELS_PAGE_SIZE },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

/** The descriptors the code ships; they change only with a deploy, so a loaded list stays fresh. */
export function useModelDescriptors(enabled = true) {
  return useQuery({
    queryKey: DESCRIPTORS_KEY,
    queryFn: () => unwrapSuccess(listAdminAiModelDescriptors(authHeaders())),
    enabled,
    staleTime: 5 * 60 * 1000,
  });
}

/** The capability keys cover the list, the detail and every models page, so one prefix refreshes all. */
function useRefreshCapabilities() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: CAPABILITIES_KEY });
}

export function useReplaceCapabilityMutation(capability: string) {
  const refresh = useRefreshCapabilities();
  return useMutation({
    mutationFn: (body: CatalogCapabilityRequest) =>
      unwrapSuccess(replaceAdminAiCapability(capability, body, authHeaders())),
    onSuccess: refresh,
  });
}

export function useCreateCatalogModelMutation(capability: string) {
  const refresh = useRefreshCapabilities();
  return useMutation({
    mutationFn: (body: CatalogModelCreateRequest) =>
      unwrapSuccess(createAdminAiCatalogModel(capability, body, authHeaders())),
    onSuccess: refresh,
  });
}

export function useReplaceCatalogModelMutation(capability: string) {
  const refresh = useRefreshCapabilities();
  return useMutation({
    mutationFn: ({
      modelKey,
      body,
    }: {
      readonly modelKey: string;
      readonly body: CatalogModelReplaceRequest;
    }) =>
      unwrapSuccess(replaceAdminAiCatalogModel(capability, body, { key: modelKey }, authHeaders())),
    onSuccess: refresh,
  });
}

export function useDeleteCatalogModelMutation(capability: string) {
  const refresh = useRefreshCapabilities();
  return useMutation({
    mutationFn: (modelKey: string) =>
      unwrapSuccess(deleteAdminAiCatalogModel(capability, { key: modelKey }, authHeaders())),
    onSuccess: refresh,
  });
}
