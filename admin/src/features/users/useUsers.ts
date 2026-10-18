import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { authHeaders, create, get, list, unwrap, update } from '@flow-cortex/api-client';
import type { CreateUserRequest, UpdateUserRequest, UserResponse } from '@flow-cortex/api-client';

const USERS_QUERY_KEY = ['users'] as const;

/** ADMIN-only user list, owner-scoped server-side. */
export function useUsersQuery() {
  return useQuery({
    queryKey: USERS_QUERY_KEY,
    queryFn: () => unwrap(list(authHeaders())),
  });
}

export function useUserQuery(id: string) {
  return useQuery({
    queryKey: ['user', id],
    queryFn: () => unwrap(get(id, authHeaders())),
    enabled: id.length > 0,
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
    onSuccess: (_user: UserResponse, { id }) => {
      queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY });
      queryClient.invalidateQueries({ queryKey: ['user', id] });
    },
  });
}
