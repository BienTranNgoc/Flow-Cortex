import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { _delete, create, list, update } from '../../api/generated/flowCortexApi';
import type { CreateUserRequest, UpdateUserRequest } from '../../api/generated/flowCortexApi';
import { authHeaders, unwrap } from '../../api/client';

const USERS_QUERY_KEY = ['users'] as const;

/** ADMIN-only user list, owner-scoped server-side. */
export function useUsersQuery() {
  return useQuery({
    queryKey: USERS_QUERY_KEY,
    queryFn: () => unwrap(list(authHeaders())),
  });
}

export function useCreateUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateUserRequest) => unwrap(create(input, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY }),
  });
}

export function useUpdateUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: UpdateUserRequest }) =>
      unwrap(update(id, changes, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY }),
  });
}

export function useDeleteUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: string) => unwrap(_delete(id, authHeaders())),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY }),
  });
}
