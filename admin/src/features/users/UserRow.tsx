import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import type { UpdateUserRequest, UserResponse } from '@flow-cortex/api-client';
import { Link } from 'react-router-dom';
import { can } from '../../app/permissions';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { StatusPill } from '../../app/StatusPill';
import { assignableRoles, ROLE_CODES, roleLabel, stateLabel, stateTone } from './enumLabels';
import { RoleOptions } from './RoleOptions';
import { canManageAccount } from './userAccess';
import { editUserSchema, type EditUserFormValues } from './userSchemas';
import { useUpdateUserMutation } from './useUsers';

/** One row of the users table — view mode, or an inline edit form. */
export function UserRow({ user }: Readonly<{ user: UserResponse }>) {
  const { t } = useTranslation();
  const me = useMeQuery().data;
  const [editing, setEditing] = useState(false);
  const updateMutation = useUpdateUserMutation();
  const assignable = assignableRoles(me);
  const role = ROLE_CODES.find((code) => code === user.role) ?? 'MEMBER';
  const status = user.status === 'SUSPENDED' ? 'SUSPENDED' : 'ACTIVE';
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
      status,
    },
  });

  if (!user.id) {
    return null;
  }
  const id = user.id;

  const onSave = handleSubmit((values) => {
    const changes: UpdateUserRequest = { email: values.email, name: values.name };
    if (values.role !== role) changes.role = values.role;
    if (values.status !== status) changes.status = values.status;
    updateMutation.mutate({ id, changes }, { onSuccess: () => setEditing(false) });
  });

  const updateError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem : undefined;
  const initials = user.name?.trim().charAt(0).toUpperCase() || '?';

  const manageable = canManageAccount(me, user);

  if (editing && manageable) {
    return (
      <tr>
        <td colSpan={6}>
          <form
            onSubmit={onSave}
            noValidate
            aria-label={t('users.row.editForm', { target: user.email ?? id })}
          >
            <div className="cortex-admin-field">
              <label htmlFor={`edit-email-${id}`}>{t('users.row.email')}</label>
              <input id={`edit-email-${id}`} type="email" {...register('email')} />
              {errors.email?.message && (
                <p className="cortex-admin-form-error" role="alert">
                  {t(errors.email.message)}
                </p>
              )}
              {updateError?.errors?.email && (
                <p className="cortex-admin-form-error" role="alert">
                  {updateError.errors.email}
                </p>
              )}
            </div>
            <div className="cortex-admin-field">
              <label htmlFor={`edit-name-${id}`}>{t('users.row.name')}</label>
              <input id={`edit-name-${id}`} {...register('name')} />
              {errors.name?.message && (
                <p className="cortex-admin-form-error" role="alert">
                  {t(errors.name.message)}
                </p>
              )}
            </div>
            <div className="cortex-admin-field">
              <label htmlFor={`edit-role-${id}`}>{t('users.row.role')}</label>
              <select
                id={`edit-role-${id}`}
                disabled={assignable.length === 0}
                {...register('role')}
              >
                <RoleOptions roles={assignable} current={role} />
              </select>
            </div>
            <div className="cortex-admin-field">
              <label htmlFor={`edit-status-${id}`}>{t('users.row.status')}</label>
              <select
                id={`edit-status-${id}`}
                disabled={!can(me, 'user.account.suspend')}
                {...register('status')}
              >
                <option value="ACTIVE">{t('users.status.ACTIVE')}</option>
                <option value="SUSPENDED">{t('users.status.SUSPENDED')}</option>
              </select>
            </div>
            {updateError && !updateError.errors && (
              <p className="cortex-admin-form-error" role="alert">
                {updateError.detail}
              </p>
            )}
            <button
              type="submit"
              className="cortex-admin-btn cortex-admin-btn--primary"
              disabled={updateMutation.isPending}
            >
              {updateMutation.isPending ? t('users.row.saving') : t('users.row.save')}
            </button>
            <button type="button" className="cortex-admin-btn" onClick={() => setEditing(false)}>
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
        <span className="cortex-admin-user-cell">
          <span className="cortex-admin-user-avatar" aria-hidden="true">
            {initials}
          </span>
          {user.name}
        </span>
      </td>
      <td data-label={t('users.columns.email')}>{user.email}</td>
      <td data-label={t('users.columns.role')}>
        <span className="cortex-admin-pill cortex-admin-pill--info">{roleLabel(t, user.role)}</span>
      </td>
      <td data-label={t('users.columns.state')}>
        <StatusPill tone={stateTone(user.state)} label={stateLabel(t, user.state)} />
      </td>
      <td data-label={t('users.columns.joined')}>
        {user.createdAt ? (
          <time dateTime={user.createdAt}>{formatDateTime(user.createdAt, activeLocale())}</time>
        ) : (
          '—'
        )}
      </td>
      <td data-label={t('users.columns.actions')}>
        <div className="cortex-admin-row-actions">
          <Link className="cortex-admin-link" to={`/users/${id}`}>
            {t('users.row.view')}
          </Link>
          {manageable ? (
            <button
              type="button"
              className="cortex-admin-btn cortex-admin-btn--sm"
              onClick={() => setEditing(true)}
            >
              {t('users.row.edit')}
            </button>
          ) : (
            <span className="cortex-admin-stale">{t('users.readOnlyAdminAccount')}</span>
          )}
        </div>
      </td>
    </tr>
  );
}
