import { zodResolver } from '@hookform/resolvers/zod';
import { useMemo, useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError } from '@flow-cortex/api-client';
import { UserRow } from './UserRow';
import './UsersPage.css';
import { createUserSchema, type CreateUserFormValues } from './userSchemas';
import { usePageTitle } from '../../app/usePageTitle';
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
    <main className="users-page">
      <div className="page-heading">
        <div>
          <p className="eyebrow">Administration</p>
          <h1>Users</h1>
          <p className="page-description">
            Manage account access and roles. Account history is preserved when access is suspended.
          </p>
        </div>
        <button
          className="button-primary"
          type="button"
          onClick={() => setShowCreateForm((open) => !open)}
        >
          {showCreateForm ? 'Cancel' : 'Add user'}
        </button>
      </div>

      {usersQuery.data && (
        <section className="metric-grid" aria-label="User statistics">
          <article className="metric-card">
            <span>Total users</span>
            <strong>{users.length}</strong>
          </article>
          <article className="metric-card">
            <span>Active</span>
            <strong>{users.filter((user) => user.status === 'ACTIVE').length}</strong>
          </article>
          <article className="metric-card">
            <span>Suspended</span>
            <strong>{users.filter((user) => user.status === 'SUSPENDED').length}</strong>
          </article>
          <article className="metric-card">
            <span>New in 7 days</span>
            <strong>{newThisWeek}</strong>
          </article>
        </section>
      )}

      {showCreateForm && (
        <form className="create-user-panel" onSubmit={onCreate} noValidate aria-label="Add user">
          <h2>Create user</h2>
          <div className="field">
            <label htmlFor="new-user-email">Email</label>
            <input id="new-user-email" type="email" autoComplete="off" {...register('email')} />
            {errors.email && <p role="alert">{errors.email.message}</p>}
            {createError?.errors?.email && <p role="alert">{createError.errors.email}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-password">Password</label>
            <input
              id="new-user-password"
              type="password"
              autoComplete="new-password"
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-name">Name</label>
            <input id="new-user-name" {...register('name')} />
            {errors.name && <p role="alert">{errors.name.message}</p>}
          </div>
          <div className="field">
            <label htmlFor="new-user-role">Role</label>
            <select id="new-user-role" {...register('role')}>
              <option value="MEMBER">Member</option>
              <option value="ADMIN">Admin</option>
            </select>
          </div>
          {createError && !createError.errors && <p role="alert">{createError.detail}</p>}
          <button type="submit" disabled={createMutation.isPending}>
            {createMutation.isPending ? 'Creating…' : 'Create user'}
          </button>
        </form>
      )}

      <section className="users-list-panel" aria-label="User directory">
        <div className="section-heading">
          <div>
            <h2>All users</h2>
            {usersQuery.data && (
              <p>
                {visibleUsers.length} of {users.length} accounts
              </p>
            )}
          </div>
          <button
            type="button"
            onClick={() => usersQuery.refetch()}
            disabled={usersQuery.isFetching}
          >
            {usersQuery.isFetching ? 'Refreshing…' : 'Refresh'}
          </button>
        </div>
        <div className="user-filters">
          <div className="field">
            <label htmlFor="users-search">Search</label>
            <input
              id="users-search"
              type="search"
              value={search}
              onChange={(event) => setSearch(event.target.value)}
              placeholder="Name or email"
            />
          </div>
          <div className="field">
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
          <div className="field">
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
        {usersQuery.isLoading && <output>Loading users…</output>}
        {usersQuery.isError && (
          <>
            <p role="alert">Could not load users.</p>
            <button type="button" onClick={() => usersQuery.refetch()}>
              Retry
            </button>
          </>
        )}
        {usersQuery.data?.length === 0 && (
          <p className="list-empty">No users yet — add one above.</p>
        )}
        {usersQuery.data && users.length > 0 && visibleUsers.length === 0 && (
          <p className="list-empty">No users match these filters.</p>
        )}
        {visibleUsers.length > 0 && (
          <div className="users-table-wrapper">
            <table className="users-table">
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
    </main>
  );
}
