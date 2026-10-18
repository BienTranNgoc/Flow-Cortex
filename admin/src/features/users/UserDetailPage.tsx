import { ApiError } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { roleLabel, statusLabel } from './enumLabels';
import { useUpdateUserMutation, useUserQuery } from './useUsers';

const userIdSchema = z.string().uuid();
const userRoleSchema = z.enum(['ADMIN', 'MEMBER', 'CREATOR']);

export function UserDetailPage() {
  const { t } = useTranslation();
  usePageTitle(t('users.detail.title'));
  const { userId = '' } = useParams();
  const parsedId = userIdSchema.safeParse(userId);
  const userQuery = useUserQuery(parsedId.success ? parsedId.data : '');
  const updateMutation = useUpdateUserMutation();
  const [role, setRole] = useState('');
  const [roleErrorKey, setRoleErrorKey] = useState('');

  if (!parsedId.success) {
    return (
      <main>
        <h1>{t('users.detail.notFound')}</h1>
        <p className="list-error" role="alert">
          {t('users.detail.invalidId')}
        </p>
        <Link className="table-link" to="/users">
          {t('users.detail.backToUsers')}
        </Link>
      </main>
    );
  }

  if (userQuery.isLoading) {
    return (
      <main>
        <h1>{t('users.detail.title')}</h1>
        <output>{t('users.detail.loading')}</output>
      </main>
    );
  }

  if (userQuery.isError || !userQuery.data) {
    return (
      <main>
        <h1>{t('users.detail.title')}</h1>
        <div className="list-error">
          <p role="alert">{t('users.detail.loadError')}</p>
          <button type="button" onClick={() => userQuery.refetch()}>
            {t('users.detail.retry')}
          </button>
          <Link className="table-link" to="/users">
            {t('users.detail.backToUsers')}
          </Link>
        </div>
      </main>
    );
  }

  const user = userQuery.data;
  const updateError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem : undefined;
  const nextStatus = user.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED';
  const saveRole = () => {
    const parsedRole = userRoleSchema.safeParse(role || user.role || 'MEMBER');
    if (!parsedRole.success) {
      setRoleErrorKey('users.detail.invalidRole');
      return;
    }
    setRoleErrorKey('');
    updateMutation.mutate({ id: parsedId.data, changes: { role: parsedRole.data } });
  };

  return (
    <main className="user-detail-page">
      <Link className="back-link" to="/users">
        {t('users.detail.allUsers')}
      </Link>
      <div className="page-heading">
        <div className="user-profile-heading">
          <span className="user-avatar user-avatar-large" aria-hidden="true">
            {user.name?.trim().charAt(0).toUpperCase() || '?'}
          </span>
          <div>
            <p className="eyebrow">{t('users.detail.eyebrow')}</p>
            <h1>{user.name || user.email}</h1>
            <p className="page-description">{user.email}</p>
          </div>
        </div>
        <span
          className={`status-badge ${user.status === 'ACTIVE' ? 'status-success' : 'status-warning'}`}
        >
          {statusLabel(t, user.status)}
        </span>
      </div>

      <div className="detail-grid">
        <section className="detail-panel">
          <div className="section-heading">
            <div>
              <h2>{t('users.detail.accountInfo')}</h2>
              <p>{t('users.detail.accountInfoHint')}</p>
            </div>
          </div>
          <dl className="user-meta-list">
            <div>
              <dt>{t('users.detail.userId')}</dt>
              <dd>
                <code>{user.id}</code>
              </dd>
            </div>
            <div>
              <dt>{t('users.detail.email')}</dt>
              <dd>{user.email}</dd>
            </div>
            <div>
              <dt>{t('users.detail.role')}</dt>
              <dd>{roleLabel(t, user.role)}</dd>
            </div>
            <div>
              <dt>{t('users.detail.status')}</dt>
              <dd>{statusLabel(t, user.status)}</dd>
            </div>
            <div>
              <dt>{t('users.detail.registered')}</dt>
              <dd>
                <Timestamp value={user.createdAt} />
              </dd>
            </div>
            <div>
              <dt>{t('users.detail.lastUpdated')}</dt>
              <dd>
                <Timestamp value={user.updatedAt} />
              </dd>
            </div>
          </dl>
        </section>

        <section className="detail-panel">
          <div className="section-heading">
            <div>
              <h2>{t('users.detail.access')}</h2>
              <p>{t('users.detail.accessHint')}</p>
            </div>
          </div>
          <div className="field">
            <label htmlFor="user-role">{t('users.detail.role')}</label>
            <select
              id="user-role"
              value={role || user.role || 'MEMBER'}
              onChange={(event) => {
                setRole(event.target.value);
                setRoleErrorKey('');
              }}
            >
              <option value="MEMBER">{t('users.roleOptions.MEMBER')}</option>
              <option value="ADMIN">{t('users.roleOptions.ADMIN')}</option>
              <option value="CREATOR">{t('users.roleOptions.CREATOR')}</option>
            </select>
          </div>
          <button
            type="button"
            onClick={saveRole}
            disabled={updateMutation.isPending || (role || user.role) === user.role}
          >
            {updateMutation.isPending ? t('users.detail.saving') : t('users.detail.saveRole')}
          </button>
          {roleErrorKey && <p role="alert">{t(roleErrorKey)}</p>}
          <button
            className={nextStatus === 'SUSPENDED' ? 'button-danger' : ''}
            type="button"
            onClick={() =>
              updateMutation.mutate({ id: parsedId.data, changes: { status: nextStatus } })
            }
            disabled={updateMutation.isPending}
          >
            {nextStatus === 'SUSPENDED' ? t('users.detail.suspend') : t('users.detail.reactivate')}
          </button>
          {updateError && <p role="alert">{updateError.detail}</p>}
          {updateMutation.isSuccess && (
            <output className="success-note">{t('users.detail.updated')}</output>
          )}
        </section>
      </div>
    </main>
  );
}
