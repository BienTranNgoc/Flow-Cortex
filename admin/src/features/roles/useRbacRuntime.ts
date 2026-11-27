import {
  authHeaders,
  getRbacRuntime,
  unwrap,
  updateRbacRuntime,
  type RbacRuntime,
  type RbacRuntimeUpdate,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { can } from '../../app/permissions';
import { useMeQuery } from '../auth/useAuth';

const RUNTIME_KEY = ['admin', 'rbac-runtime'] as const;

/** The kill switch for role and permission edits; `enabled` is false for a caller who may not manage roles. */
export function useRbacRuntimeQuery(enabled: boolean) {
  return useQuery({
    queryKey: RUNTIME_KEY,
    queryFn: () => unwrap(getRbacRuntime(authHeaders())),
    enabled,
  });
}

/** The server answers the new state; it replaces the cached read so both pages follow at once. */
export function useUpdateRbacRuntimeMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (request: RbacRuntimeUpdate) => unwrap(updateRbacRuntime(request, authHeaders())),
    onSuccess: (runtime: RbacRuntime) => queryClient.setQueryData(RUNTIME_KEY, runtime),
  });
}

/**
 * Whether the Roles pages may offer edits: the caller holds the manage key and the switch read on.
 * A loading or failed read leaves `editable` false, so edits stay disabled until the state is known.
 */
export function useRolesEditable() {
  const me = useMeQuery().data;
  const canManage = can(me, 'system.role.manage');
  const runtime = useRbacRuntimeQuery(canManage);
  return { canManage, runtime, editable: canManage && runtime.data?.mutationsEnabled === true };
}
