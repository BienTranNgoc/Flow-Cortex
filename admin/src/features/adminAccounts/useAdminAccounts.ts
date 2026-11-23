import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  activateAdminAccount,
  authHeaders,
  changeAdminAccountRole,
  deactivateAdminAccount,
  listAdminAccounts,
  unwrap,
} from '@flow-cortex/api-client';

export const ACCOUNTS_PAGE_SIZE = 20;

const ACCOUNTS_KEY = ['admin', 'accounts'] as const;

/** One page of admin accounts (staff and super admins), newest first; the previous page stays while the next loads. */
export function useAdminAccounts(page: number, role: string, q: string) {
  return useQuery({
    queryKey: [...ACCOUNTS_KEY, page, role, q],
    queryFn: () =>
      unwrap(
        listAdminAccounts(
          { page, size: ACCOUNTS_PAGE_SIZE, ...(role ? { role } : {}), ...(q ? { q } : {}) },
          authHeaders(),
        ),
      ),
    placeholderData: keepPreviousData,
  });
}

function useRefreshAccounts() {
  const queryClient = useQueryClient();
  return () =>
    Promise.all([
      queryClient.invalidateQueries({ queryKey: ACCOUNTS_KEY }),
      queryClient.invalidateQueries({ queryKey: ['users'] }),
      queryClient.invalidateQueries({ queryKey: ['admin', 'roles'] }),
    ]);
}

export function useChangeAdminRoleMutation() {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: ({ id, role }: { id: string; role: string }) =>
      unwrap(changeAdminAccountRole(id, { role }, authHeaders())),
    onSuccess: refresh,
  });
}

export function useSetAdminAccountActiveMutation() {
  const refresh = useRefreshAccounts();
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      unwrap(
        active
          ? activateAdminAccount(id, authHeaders())
          : deactivateAdminAccount(id, authHeaders()),
      ),
    onSuccess: refresh,
  });
}
