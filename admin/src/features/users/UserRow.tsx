import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import type { UserResponse } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { roleLabel, statusLabel } from './enumLabels';
import { editUserSchema, type EditUserFormValues } from './userSchemas';
import { useUpdateUserMutation } from './useUsers';

/** One row of the users table — view mode, or an inline edit form. */
export function UserRow({ user }: Readonly<{ user: UserResponse }>) {
  const { t } = useTranslation();
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
          <form
            onSubmit={onSave}
            noValidate
            aria-label={t('users.row.editForm', { target: user.email ?? id })}
          >
            <div className="field">
              <label htmlFor={`edit-email-${id}`}>{t('users.row.email')}</label>
              <input id={`edit-email-${id}`} type="email" {...register('email')} />
              {errors.email?.message && <p role="alert">{t(errors.email.message)}</p>}
              {updateError?.errors?.email && <p role="alert">{updateError.errors.email}</p>}
            </div>
            <div className="field">
              <label htmlFor={`edit-name-${id}`}>{t('users.row.name')}</label>
              <input id={`edit-name-${id}`} {...register('name')} />
              {errors.name?.message && <p role="alert">{t(errors.name.message)}</p>}
            </div>
            <div className="field">
              <label htmlFor={`edit-role-${id}`}>{t('users.row.role')}</label>
              <select id={`edit-role-${id}`} {...register('role')}>
                <option value="MEMBER">{t('users.roleOptions.MEMBER')}</option>
                <option value="ADMIN">{t('users.roleOptions.ADMIN')}</option>
                <option value="CREATOR">{t('users.roleOptions.CREATOR')}</option>
              </select>
            </div>
            <div className="field">
              <label htmlFor={`edit-status-${id}`}>{t('users.row.status')}</label>
              <select id={`edit-status-${id}`} {...register('status')}>
                <option value="ACTIVE">{t('users.status.ACTIVE')}</option>
                <option value="SUSPENDED">{t('users.status.SUSPENDED')}</option>
              </select>
            </div>
            {updateError && !updateError.errors && <p role="alert">{updateError.detail}</p>}
            <button type="submit" disabled={updateMutation.isPending}>
              {updateMutation.isPending ? t('users.row.saving') : t('users.row.save')}
            </button>
            <button type="button" onClick={() => setEditing(false)}>
              {t('users.row.cancel')}
            </button>
          </form>
        </td>
      </tr>
    );
  }

  return (
    <tr>
      <td data-label={t('users.columns.name')}>
        <span className="user-name-cell">
          <span className="user-avatar" aria-hidden="true">
            {user.name?.trim().charAt(0).toUpperCase() || '?'}
          </span>
          {user.name}
        </span>
      </td>
      <td data-label={t('users.columns.email')}>{user.email}</td>
      <td data-label={t('users.columns.role')}>
        <span className="status-badge">{roleLabel(t, user.role)}</span>
      </td>
      <td data-label={t('users.columns.status')}>
        <span
          className={`status-badge ${user.status === 'ACTIVE' ? 'status-success' : 'status-warning'}`}
        >
          {statusLabel(t, user.status)}
        </span>
      </td>
      <td data-label={t('users.columns.joined')}>
        {user.createdAt ? (
          <time dateTime={user.createdAt}>{formatDateTime(user.createdAt, activeLocale())}</time>
        ) : (
          '—'
        )}
      </td>
      <td data-label={t('users.columns.actions')}>
        <Link className="table-link" to={`/users/${id}`}>
          {t('users.row.view')}
        </Link>
        <button type="button" onClick={() => setEditing(true)}>
          {t('users.row.edit')}
        </button>
      </td>
    </tr>
  );
}
