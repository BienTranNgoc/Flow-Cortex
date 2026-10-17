import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { ApiError } from '@flow-cortex/api-client';
import type { UserResponse } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { editUserSchema, type EditUserFormValues } from './userSchemas';
import { useUpdateUserMutation } from './useUsers';

/** One row of the users table — view mode, or an inline edit form. */
export function UserRow({ user }: Readonly<{ user: UserResponse }>) {
  const [editing, setEditing] = useState(false);
  const updateMutation = useUpdateUserMutation();
  const role = user.role === 'ADMIN' || user.role === 'CREATOR' ? user.role : 'MEMBER';
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EditUserFormValues>({
    resolver: zodResolver(editUserSchema),
    values: {
      email: user.email ?? '',
      name: user.name ?? '',
      role,
      status: user.status === 'SUSPENDED' ? 'SUSPENDED' : 'ACTIVE',
    },
  });

  if (!user.id) {
    return null;
  }
  const id = user.id;

  const onSave = handleSubmit((values) => {
    updateMutation.mutate({ id, changes: values }, { onSuccess: () => setEditing(false) });
  });

  const updateError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem : undefined;

  if (editing) {
    return (
      <tr>
        <td colSpan={6}>
          <form onSubmit={onSave} noValidate aria-label={`Edit ${user.email ?? id}`}>
            <div className="field">
              <label htmlFor={`edit-email-${id}`}>Email</label>
              <input id={`edit-email-${id}`} type="email" {...register('email')} />
              {errors.email && <p role="alert">{errors.email.message}</p>}
              {updateError?.errors?.email && <p role="alert">{updateError.errors.email}</p>}
            </div>
            <div className="field">
              <label htmlFor={`edit-name-${id}`}>Name</label>
              <input id={`edit-name-${id}`} {...register('name')} />
              {errors.name && <p role="alert">{errors.name.message}</p>}
            </div>
            <div className="field">
              <label htmlFor={`edit-role-${id}`}>Role</label>
              <select id={`edit-role-${id}`} {...register('role')}>
                <option value="MEMBER">Member</option>
                <option value="ADMIN">Admin</option>
                <option value="CREATOR">Creator</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor={`edit-status-${id}`}>Status</label>
              <select id={`edit-status-${id}`} {...register('status')}>
                <option value="ACTIVE">Active</option>
                <option value="SUSPENDED">Suspended</option>
              </select>
            </div>
            {updateError && !updateError.errors && <p role="alert">{updateError.detail}</p>}
            <button type="submit" disabled={updateMutation.isPending}>
              {updateMutation.isPending ? 'Saving…' : 'Save'}
            </button>
            <button type="button" onClick={() => setEditing(false)}>
              Cancel
            </button>
          </form>
        </td>
      </tr>
    );
  }

  return (
    <tr>
      <td data-label="Name">
        <span className="user-name-cell">
          <span className="user-avatar" aria-hidden="true">
            {user.name?.trim().charAt(0).toUpperCase() || '?'}
          </span>
          {user.name}
        </span>
      </td>
      <td data-label="Email">{user.email}</td>
      <td data-label="Role">
        <span className="status-badge">{user.role}</span>
      </td>
      <td data-label="Status">
        <span
          className={`status-badge ${user.status === 'ACTIVE' ? 'status-success' : 'status-warning'}`}
        >
          {user.status === 'SUSPENDED' ? 'Suspended' : 'Active'}
        </span>
      </td>
      <td data-label="Joined">
        {user.createdAt ? (
          <time dateTime={user.createdAt}>{new Date(user.createdAt).toLocaleDateString()}</time>
        ) : (
          '—'
        )}
      </td>
      <td data-label="Actions">
        <Link className="table-link" to={`/users/${id}`}>
          View
        </Link>
        <button type="button" onClick={() => setEditing(true)}>
          Edit
        </button>
      </td>
    </tr>
  );
}
