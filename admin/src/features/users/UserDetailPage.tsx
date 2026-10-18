import { ApiError } from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ArrowLeft } from 'lucide-react';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
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
      <div>
        <h1>{t('users.detail.notFound')}</h1>
        <p className="cortex-admin-error" role="alert">
          {t('users.detail.invalidId')}
        </p>
        <Link className="cortex-admin-link" to="/users">
          {t('users.detail.backToUsers')}
        </Link>
      </div>
    );
  }

  if (userQuery.isLoading) {
    return (
      <div>
        <h1>{t('users.detail.title')}</h1>
        <output className="cortex-admin-loading">{t('users.detail.loading')}</output>
      </div>
    );
  }

  if (userQuery.isError || !userQuery.data) {
    return (
      <div>
        <h1>{t('users.detail.title')}</h1>
        <div className="cortex-admin-error">
          <p role="alert">{t('users.detail.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => userQuery.refetch()}>
            {t('users.detail.retry')}
          </button>
          <Link className="cortex-admin-link" to="/users">
            {t('users.detail.backToUsers')}
          </Link>
        </div>
      </div>
    );
  }

  const user = userQuery.data;
  const updateError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem : undefined;
  const nextStatus = user.status === 'SUSPENDED' ? 'ACTIVE' : 'SUSPENDED';
  const initials = user.name?.trim().charAt(0).toUpperCase() || '?';
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
    <div>
      <Link className="cortex-admin-back-link" to="/users">
        <ArrowLeft size={16} aria-hidden="true" /> {t('users.detail.allUsers')}
      </Link>
      <PageHeading
        eyebrow={t('users.detail.eyebrow')}
        title={user.name || user.email || t('users.detail.userFallback')}
        description={user.email}
        actions={
          <span
            className={
              user.status === 'ACTIVE'
                ? 'cortex-admin-pill cortex-admin-pill--success'
                : 'cortex-admin-pill cortex-admin-pill--warning'
            }
          >
            {statusLabel(t, user.status)}
          </span>
        }
      />

      <div className="cortex-admin-detail-grid">
        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>{t('users.detail.accountInfo')}</h2>
              <p>{t('users.detail.accountInfoHint')}</p>
            </div>
          </header>
          <div className="cortex-admin-user-cell">
            <span
              className="cortex-admin-user-avatar cortex-admin-user-avatar--lg"
              aria-hidden="true"
            >
              {initials}
            </span>
            <div>
              <strong>{user.name || '—'}</strong>
              <p className="cortex-admin-page-description">{user.email}</p>
            </div>
          </div>
          <hr className="cortex-admin-divider" />
          <dl className="cortex-admin-meta-list">
            <div>
              <dt>{t('users.detail.userId')}</dt>
              <dd>
                <code className="cortex-admin-table-job-id">{user.id}</code>
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
              <dd>
                <StatusPill
                  tone={user.status === 'ACTIVE' ? 'success' : 'warning'}
                  label={statusLabel(t, user.status)}
                />
              </dd>
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

        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>{t('users.detail.access')}</h2>
              <p>{t('users.detail.accessHint')}</p>
            </div>
          </header>
          <div className="cortex-admin-field">
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
            className="cortex-admin-btn cortex-admin-btn--primary"
            onClick={saveRole}
            disabled={updateMutation.isPending || (role || user.role) === user.role}
          >
            {updateMutation.isPending ? t('users.detail.saving') : t('users.detail.saveRole')}
          </button>
          {roleErrorKey && (
            <p className="cortex-admin-form-error" role="alert">
              {t(roleErrorKey)}
            </p>
          )}
          <hr className="cortex-admin-divider" />
          <button
            type="button"
            className={
              nextStatus === 'SUSPENDED'
                ? 'cortex-admin-btn cortex-admin-btn--danger'
                : 'cortex-admin-btn'
            }
            onClick={() =>
              updateMutation.mutate({ id: parsedId.data, changes: { status: nextStatus } })
            }
            disabled={updateMutation.isPending}
          >
            {nextStatus === 'SUSPENDED' ? t('users.detail.suspend') : t('users.detail.reactivate')}
          </button>
          {updateError && (
            <p className="cortex-admin-form-error" role="alert">
              {updateError.detail}
            </p>
          )}
          {updateMutation.isSuccess && (
            <p className="cortex-admin-form-success">{t('users.detail.updated')}</p>
          )}
        </section>
      </div>
    </div>
  );
}
