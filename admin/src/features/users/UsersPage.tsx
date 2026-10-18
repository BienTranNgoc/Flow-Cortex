import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError } from '@flow-cortex/api-client';
import { Plus, RefreshCw } from 'lucide-react';
import { UserRow } from './UserRow';
import { createUserSchema, type CreateUserFormValues } from './userSchemas';
import { usePageTitle } from '../../app/usePageTitle';
import { PageHeading } from '../../app/PageHeading';
import { MetricCard } from '../../app/MetricCard';
import { EmptyState } from '../../app/EmptyState';
import { useCreateUserMutation, useUsersQuery } from './useUsers';

const EMPTY_USERS: NonNullable<ReturnType<typeof useUsersQuery>['data']> = [];

export function UsersPage() {
  usePageTitle('Users');
  const usersQuery = useUsersQuery();
  const createMutation = useCreateUserMutation();
  const [showCreateForm, setShowCreateForm] = useState(false);
  const [search, setSearch] = useState('');
  const [statusFilter, setStatusFilter] = useState('');
  const [roleFilter, setRoleFilter] = useState('');
  const {
    register,
    handleSubmit,
    reset,
    formState: { errors },
  } = useForm<CreateUserFormValues>({
    resolver: zodResolver(createUserSchema),
    defaultValues: { email: '', password: '', name: '', role: 'MEMBER' },
  });

  const onCreate = handleSubmit((values) => {
    createMutation.mutate(values, {
      onSuccess: () => {
        reset({ email: '', password: '', name: '', role: 'MEMBER' });
        setShowCreateForm(false);
      },
    });
  });

  const createError =
    createMutation.error instanceof ApiError ? createMutation.error.problem : undefined;
  const users = usersQuery.data ?? EMPTY_USERS;
  const visibleUsers = useMemo(() => {
    const needle = search.trim().toLocaleLowerCase();
    return users.filter((user) => {
      const matchesSearch =
        !needle ||
        user.email?.toLocaleLowerCase().includes(needle) ||
        user.name?.toLocaleLowerCase().includes(needle);
      return (
        matchesSearch &&
        (!statusFilter || user.status === statusFilter) &&
        (!roleFilter || user.role === roleFilter)
      );
    });
  }, [users, search, statusFilter, roleFilter]);
  const sevenDaysAgo = Date.now() - 7 * 24 * 60 * 60 * 1000;
  const newThisWeek = users.filter(
    (user) => user.createdAt && Date.parse(user.createdAt) >= sevenDaysAgo,
  ).length;

  return (
    <div className="cortex-admin-users">
      <PageHeading
        eyebrow="Administration"
        title="Users"
        description="Manage account access and roles. Account history is preserved when access is suspended."
        actions={
          <>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--ghost"
              onClick={() => usersQuery.refetch()}
              disabled={usersQuery.isFetching}
            >
              <RefreshCw size={16} aria-hidden="true" />
              <span>{usersQuery.isFetching ? 'Refreshing…' : 'Refresh'}</span>
            </button>
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--primary"
              onClick={() => setShowCreateForm((open) => !open)}
            >
              <Plus size={16} aria-hidden="true" />
              <span>{showCreateForm ? 'Cancel' : 'Add user'}</span>
            </button>
          </>
        }
      />

      {usersQuery.data && (
        <section className="cortex-admin-metric-grid" aria-label="User statistics">
          <MetricCard label="Total users" value={users.length} />
          <MetricCard
            label="Active"
            value={users.filter((user) => user.status === 'ACTIVE').length}
          />
          <MetricCard
            label="Suspended"
            value={users.filter((user) => user.status === 'SUSPENDED').length}
          />
          <MetricCard label="New in 7 days" value={newThisWeek} />
        </section>
      )}

      {showCreateForm && (
        <form
          className="cortex-admin-panel"
          onSubmit={onCreate}
          noValidate
          aria-label="Add user"
        >
          <header className="cortex-admin-section-heading">
            <div>
              <h2>Create user</h2>
              <p>Sets the initial password and role. The user can change both later.</p>
            </div>
          </header>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-email">Email</label>
            <input id="new-user-email" type="email" autoComplete="off" {...register('email')} />
            {errors.email && (
              <p className="cortex-admin-form-error" role="alert">{errors.email.message}</p>
            )}
            {createError?.errors?.email && (
              <p className="cortex-admin-form-error" role="alert">{createError.errors.email}</p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-password">Password</label>
            <input
              id="new-user-password"
              type="password"
              autoComplete="new-password"
              {...register('password')}
            />
            {errors.password && (
              <p className="cortex-admin-form-error" role="alert">{errors.password.message}</p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-name">Name</label>
            <input id="new-user-name" {...register('name')} />
            {errors.name && (
              <p className="cortex-admin-form-error" role="alert">{errors.name.message}</p>
            )}
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="new-user-role">Role</label>
            <select id="new-user-role" {...register('role')}>
              <option value="MEMBER">Member</option>
              <option value="ADMIN">Admin</option>
            </select>
          </div>
          {createError && !createError.errors && (
            <p className="cortex-admin-form-error" role="alert">{createError.detail}</p>
          )}
          <button
            type="submit"
            className="cortex-admin-btn cortex-admin-btn--primary"
            disabled={createMutation.isPending}
          >
            {createMutation.isPending ? 'Creating…' : 'Create user'}
          </button>
        </form>
      )}

      <section className="cortex-admin-panel" aria-label="User directory">
        <header className="cortex-admin-section-heading">
          <div>
            <h2>All users</h2>
            {usersQuery.data && (
              <p>
                Showing {visibleUsers.length} of {users.length} accounts
              </p>
            )}
          </div>
        </header>
        <div className="cortex-admin-filters">
          <div className="cortex-admin-field">
            <label htmlFor="users-search">Search</label>
            <input
              id="users-search"
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Name or email"
            />
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="users-status">Status</label>
            <select
              id="users-status"
              value={statusFilter}
              onChange={(event) => setStatusFilter(event.target.value)}
            >
              <option value="">All statuses</option>
              <option value="ACTIVE">Active</option>
              <option value="SUSPENDED">Suspended</option>
            </select>
          </div>
          <div className="cortex-admin-field">
            <label htmlFor="users-role">Role</label>
            <select
              id="users-role"
              value={roleFilter}
              onChange={(event) => setRoleFilter(event.target.value)}
            >
              <option value="">All roles</option>
              <option value="ADMIN">Admin</option>
              <option value="MEMBER">Member</option>
              <option value="CREATOR">Creator</option>
            </select>
          </div>
        </div>
        {usersQuery.isLoading && <output className="cortex-admin-loading">Loading users…</output>}
        {usersQuery.isError && (
          <div className="cortex-admin-error" role="alert">
            <p>Could not load users.</p>
            <button
              type="button"
              className="cortex-admin-btn"
              onClick={() => usersQuery.refetch()}
            >
              Retry
            </button>
          </div>
        )}
        {usersQuery.data?.length === 0 && (
          <EmptyState
            title="No users yet."
            description="Add the first admin or member above."
          />
        )}
        {usersQuery.data && users.length > 0 && visibleUsers.length === 0 && (
          <EmptyState
            title="No users match these filters."
            action={
              <button
                type="button"
                className="cortex-admin-btn cortex-admin-btn--ghost"
                onClick={() => {
                  setSearch('');
                  setStatusFilter('');
                  setRoleFilter('');
                }}
              >
                Clear filters
              </button>
            }
          />
        )}
        {visibleUsers.length > 0 && (
          <div className="cortex-admin-table-wrapper">
            <table className="cortex-admin-table">
              <thead>
                <tr>
                  <th scope="col">Name</th>
                  <th scope="col">Email</th>
                  <th scope="col">Role</th>
                  <th scope="col">Status</th>
                  <th scope="col">Joined</th>
                  <th scope="col">Actions</th>
                </tr>
              </thead>
              <tbody>
                {visibleUsers.map((user) => (
                  <UserRow key={user.id} user={user} />
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}
