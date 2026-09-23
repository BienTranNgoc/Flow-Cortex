import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import type { UserResponse } from '../../api/generated/flowCortexApi';
import { ApiError } from '../../api/problemDetail';
import { editUserSchema, type EditUserFormValues } from './userSchemas';
import { useDeleteUserMutation, useUpdateUserMutation } from './useUsers';

/** One row of the Users table — view mode, or an inline edit form. */
export function UserRow({ user }: { user: UserResponse }) {
  const [editing, setEditing] = useState(false);
  const updateMutation = useUpdateUserMutation();
  const deleteMutation = useDeleteUserMutation();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<EditUserFormValues>({
    resolver: zodResolver(editUserSchema),
    values: {
      email: user.email ?? '',
      name: user.name ?? '',
      role: user.role === 'ADMIN' ? 'ADMIN' : 'MEMBER',
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

  const onDelete = () => {
    if (window.confirm(`Delete ${user.email ?? 'this user'}?`)) {
      deleteMutation.mutate(id);
    }
  };

  const updateError = updateMutation.error instanceof ApiError ? updateMutation.error.problem : undefined;
  const deleteError = deleteMutation.error instanceof ApiError ? deleteMutation.error.problem : undefined;

  if (editing) {
    return (
      <tr>
        <td colSpan={5}>
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
      <td data-label="Email">{user.email}</td>
      <td data-label="Name">{user.name}</td>
      <td data-label="Role">{user.role}</td>
      <td data-label="Status">{user.status}</td>
      <td data-label="Actions">
        <button type="button" onClick={() => setEditing(true)}>
          Edit
        </button>
        <button type="button" onClick={onDelete} disabled={deleteMutation.isPending}>
          Delete
        </button>
        {deleteError && <p role="alert">{deleteError.detail}</p>}
      </td>
    </tr>
  );
}
