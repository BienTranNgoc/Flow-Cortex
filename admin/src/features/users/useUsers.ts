import { keepPreviousData, useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import {
  ApiError,
  authHeaders,
  create,
  downloadFile,
  get,
  getExportUsersUrl,
  getUserStats,
  listUsers,
  unwrap,
  update,
} from '@flow-cortex/api-client';
import type {
  CreateUserRequest,
  ExportUsersParams,
  ListUsersParams,
  UpdateUserRequest,
  UserResponse,
} from '@flow-cortex/api-client';
import type { UsersParams } from './usersQueryParams';

export const USERS_PAGE_SIZE = 20;
const USERS_QUERY_KEY = ['users'] as const;
const USER_STATS_QUERY_KEY = ['userStats'] as const;
const EXPORT_FALLBACK_NAME = 'users.csv';

/** The filters both the list and the export route accept; empty values are left out. */
function filtersOf(params: UsersParams): ExportUsersParams {
  return {
    sort: params.sort,
    ...(params.q ? { q: params.q } : {}),
    ...(params.role ? { role: params.role } : {}),
    ...(params.state ? { state: params.state } : {}),
  };
}

/** One page of the user list, owner-scoped server-side; the previous page stays shown while the next loads. */
export function useUsersQuery(params: UsersParams, enabled = true) {
  const request: ListUsersParams = {
    ...filtersOf(params),
    page: params.page,
    size: USERS_PAGE_SIZE,
  };
  return useQuery({
    queryKey: [...USERS_QUERY_KEY, params],
    queryFn: () => unwrap(listUsers(request, authHeaders())),
    placeholderData: keepPreviousData,
    enabled,
  });
}

/** Account counts for the caller's scope; `enabled` is false for a caller without `user.account.view`. */
export function useUserStatsQuery(enabled = true) {
  return useQuery({
    queryKey: USER_STATS_QUERY_KEY,
    queryFn: () => unwrap(getUserStats(authHeaders())),
    enabled,
  });
}

export function useUserQuery(id: string) {
  return useQuery({
    queryKey: ['user', id],
    queryFn: () => unwrap(get(id, authHeaders())),
    enabled: id.length > 0,
  });
}

function invalidateDirectory(queryClient: ReturnType<typeof useQueryClient>) {
  return Promise.all([
    queryClient.invalidateQueries({ queryKey: USERS_QUERY_KEY }),
    queryClient.invalidateQueries({ queryKey: USER_STATS_QUERY_KEY }),
  ]);
}

export function useCreateUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (input: CreateUserRequest) => unwrap(create(input, authHeaders())),
    onSuccess: () => invalidateDirectory(queryClient),
  });
}

export function useUpdateUserMutation() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, changes }: { id: string; changes: UpdateUserRequest }) =>
      unwrap(update(id, changes, authHeaders())),
    onSuccess: async (_user: UserResponse, { id }) => {
      await Promise.all([
        invalidateDirectory(queryClient),
        queryClient.invalidateQueries({ queryKey: ['user', id] }),
      ]);
    },
  });
}

function saveBlob(blob: Blob, fileName: string) {
  const objectUrl = URL.createObjectURL(blob);
  try {
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = fileName;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

/** Downloads the CSV for the current search, filters and sort; a refusal is thrown as its {@link ApiError}. */
export function useExportUsers() {
  return useMutation({
    mutationFn: async (params: UsersParams) => {
      const result = await downloadFile(getExportUsersUrl(filtersOf(params)), {
        headers: {
          ...(authHeaders().headers as Record<string, string> | undefined),
          Accept: 'text/csv, application/problem+json',
        },
      });
      if (result.blob === undefined) {
        throw new ApiError(result.status, result.problem);
      }
      saveBlob(result.blob, result.fileName ?? EXPORT_FALLBACK_NAME);
    },
  });
}
