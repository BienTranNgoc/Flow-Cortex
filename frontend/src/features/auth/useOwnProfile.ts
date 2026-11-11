import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  authHeaders,
  changeUsername as changeUsernameRequest,
  get1 as getOwnProfile,
  unwrap,
  updateProfile,
  useAuthToken,
} from '@flow-cortex/api-client';
import type { ProfileUpdateRequest } from '@flow-cortex/api-client';

export function useOwnProfileQuery() {
  const token = useAuthToken();
  return useQuery({
    queryKey: ['own-profile', token],
    queryFn: () => unwrap(getOwnProfile(authHeaders())),
    enabled: Boolean(token),
  });
}

function useRefreshingOwnProfile<V>(save: (variables: V) => Promise<unknown>) {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: save,
    onSuccess: () =>
      Promise.all([
        queryClient.invalidateQueries({ queryKey: ['me'] }),
        queryClient.invalidateQueries({ queryKey: ['own-profile'] }),
      ]),
  });
}

export function useUpdateNames() {
  return useRefreshingOwnProfile((values: ProfileUpdateRequest) =>
    unwrap(updateProfile(values, authHeaders())),
  );
}

export function useChangeUsername() {
  return useRefreshingOwnProfile((username: string) =>
    unwrap(changeUsernameRequest({ username }, authHeaders())),
  );
}
