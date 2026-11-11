import {
  authHeaders,
  createAdminRole,
  deleteAdminRole,
  getAdminRole,
  listAdminPermissionCatalog,
  listAdminRoles,
  replaceAdminRolePermissions,
  unwrap,
  updateAdminRole,
  type CreateRoleRequest,
  type ReplacePermissionsRequest,
  type RoleResponse,
  type UpdateRoleRequest,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient, type QueryClient } from '@tanstack/react-query';

const ROLES_KEY = ['admin', 'roles'] as const;
const CATALOG_KEY = ['admin', 'permission-catalog'] as const;

/** A role code is path-safe by construction; anything else never reaches the API. */
export const ROLE_CODE_PATTERN = /^[A-Z][A-Z0-9_]{0,19}$/;

/** Every role, system and custom, in the order the API lists them; `enabled` is false for a caller who may not list roles. */
export function useRolesQuery(enabled = true) {
  return useQuery({
    queryKey: ROLES_KEY,
    queryFn: () => unwrap(listAdminRoles(authHeaders())),
    enabled,
  });
}

export function useRoleQuery(code: string) {
  return useQuery({
    queryKey: [...ROLES_KEY, code],
    queryFn: () => unwrap(getAdminRole(code, authHeaders())),
    enabled: ROLE_CODE_PATTERN.test(code),
  });
}

export function usePermissionCatalogQuery() {
  return useQuery({
    queryKey: CATALOG_KEY,
    queryFn: () => unwrap(listAdminPermissionCatalog(authHeaders())),
    staleTime: 5 * 60 * 1000,
  });
}

/** A write changes the list, the role's detail and, through the keys, the caller's own `auth/me`. */
function refreshAfterWrite(queryClient: QueryClient) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: ROLES_KEY }),
    queryClient.invalidateQueries({ queryKey: ['me'] }),
    queryClient.invalidateQueries({ queryKey: ['admin', 'accounts'] }),
  ]);
}

export function useCreateRoleMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: CreateRoleRequest) => unwrap(createAdminRole(request, authHeaders())),
    onSuccess: () => refreshAfterWrite(queryClient),
  });
}

export function useRenameRoleMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ code, request }: { code: string; request: UpdateRoleRequest }) =>
      unwrap(updateAdminRole(code, request, authHeaders())),
    onSuccess: () => refreshAfterWrite(queryClient),
  });
}

export function useDeleteRoleMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (code: string) => unwrap(deleteAdminRole(code, authHeaders())),
    onSuccess: () => refreshAfterWrite(queryClient),
  });
}

export function useReplacePermissionsMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ code, request }: { code: string; request: ReplacePermissionsRequest }) =>
      unwrap(replaceAdminRolePermissions(code, request, authHeaders())),
    onSuccess: (role: RoleResponse) => {
      queryClient.setQueryData([...ROLES_KEY, role.code], role);
      return refreshAfterWrite(queryClient);
    },
  });
}
