import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError } from '@flow-cortex/api-client';
import { UserRow } from './UserRow';
import './UsersPage.css';
import { createUserSchema, type CreateUserFormValues } from './userSchemas';
import { usePageTitle } from '../../app/usePageTitle';
import { useCreateUserMutation, useUsersQuery } from './useUsers';

/**
 * ADMIN-only user list + create/edit/delete. Functional MVP only — no
 * design tokens yet.
 */
export function UsersPage() {
  usePageTitle('Users');
  const usersQuery = useUsersQuery();
  const createMutation = useCreateUserMutation();
  const [showCreateForm, setShowCreateForm] = useState(false);
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

  const createError = createMutation.error instanceof ApiError ? createMutation.error.problem : undefined;

  return (
    <main>
      <h1>Users</h1>
      <button type="button" onClick={() => setShowCreateForm((open) => !open)}>
        {showCreateForm ? 'Cancel' : 'Add user'}
      </button>

      {showCreateForm && (
        <form onSubmit={onCreate} noValidate aria-label="Add user" style={{ marginTop: '1rem' }}>
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

      <div className="users-table-wrapper" style={{ marginTop: '1.5rem' }}>
        {usersQuery.isLoading && <output>Loading users…</output>}
        {usersQuery.isError && (
          <>
            <p role="alert">Could not load users.</p>
            <button type="button" onClick={() => usersQuery.refetch()}>
              Retry
            </button>
          </>
        )}
        {usersQuery.data?.length === 0 && <p>No users yet — add one above.</p>}
        {usersQuery.data && usersQuery.data.length > 0 && (
          <table className="users-table">
            <thead>
              <tr>
                <th scope="col">Email</th>
                <th scope="col">Name</th>
                <th scope="col">Role</th>
                <th scope="col">Status</th>
                <th scope="col">Actions</th>
              </tr>
            </thead>
            <tbody>
              {usersQuery.data.map((user) => (
                <UserRow key={user.id} user={user} />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </main>
  );
}
