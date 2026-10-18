import { ApiError } from '@flow-cortex/api-client';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
import { ArrowLeft } from 'lucide-react';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill } from '../../app/StatusPill';
import { Timestamp } from '../../app/AdminList';
import { usePageTitle } from '../../app/usePageTitle';
import { useUpdateUserMutation, useUserQuery } from './useUsers';

const userIdSchema = z.string().uuid();
const userRoleSchema = z.enum(['ADMIN', 'MEMBER', 'CREATOR']);

export function UserDetailPage() {
  usePageTitle('User details');
  const { userId = '' } = useParams();
  const parsedId = userIdSchema.safeParse(userId);
  const userQuery = useUserQuery(parsedId.success ? parsedId.data : '');
  const updateMutation = useUpdateUserMutation();
  const [role, setRole] = useState('');
  const [roleError, setRoleError] = useState('');

  if (!parsedId.success) {
    return (
      <div>
        <h1>User not found</h1>
        <p className="cortex-admin-error" role="alert">
          The user ID is not valid.
        </p>
        <Link className="cortex-admin-link" to="/users">
          Back to users
        </Link>
      </div>
    );
  }

  if (userQuery.isLoading) {
    return (
      <div>
        <h1>User details</h1>
        <output className="cortex-admin-loading">Loading user…</output>
      </div>
    );
  }

  if (userQuery.isError || !userQuery.data) {
    return (
      <div>
        <h1>User details</h1>
        <div className="cortex-admin-error">
          <p role="alert">Could not load this user.</p>
          <button type="button" className="cortex-admin-btn" onClick={() => userQuery.refetch()}>
            Retry
          </button>
          <Link className="cortex-admin-link" to="/users">
            Back to users
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
      setRoleError('Select a valid role.');
      return;
    }
    setRoleError('');
    updateMutation.mutate({ id: parsedId.data, changes: { role: parsedRole.data } });
  };

  return (
    <div>
      <Link className="cortex-admin-back-link" to="/users">
        <ArrowLeft size={16} aria-hidden="true" /> All users
      </Link>
      <PageHeading
        eyebrow="User account"
        title={user.name || user.email || 'User'}
        description={user.email}
        actions={
          <span
            className={
              user.status === 'ACTIVE'
                ? 'cortex-admin-pill cortex-admin-pill--success'
                : 'cortex-admin-pill cortex-admin-pill--warning'
            }
          >
            {user.status === 'SUSPENDED' ? 'Suspended' : 'Active'}
          </span>
        }
      />

      <div className="cortex-admin-detail-grid">
        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>Account information</h2>
              <p>Identity and access details.</p>
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
              <dt>User ID</dt>
              <dd>
                <code className="cortex-admin-table-job-id">{user.id}</code>
              </dd>
            </div>
            <div>
              <dt>Email</dt>
              <dd>{user.email}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd>{user.role}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>
                <StatusPill
                  tone={user.status === 'ACTIVE' ? 'success' : 'warning'}
                  label={user.status === 'SUSPENDED' ? 'Suspended' : 'Active'}
                />
              </dd>
            </div>
            <div>
              <dt>Registered</dt>
              <dd>
                <Timestamp value={user.createdAt} />
              </dd>
            </div>
            <div>
              <dt>Last updated</dt>
              <dd>
                <Timestamp value={user.updatedAt} />
              </dd>
            </div>
          </dl>
        </section>

        <section className="cortex-admin-panel">
          <header className="cortex-admin-section-heading">
            <div>
              <h2>Access management</h2>
              <p>Changes are recorded without deleting account history.</p>
            </div>
          </header>
          <div className="cortex-admin-field">
            <label htmlFor="user-role">Role</label>
            <select
              id="user-role"
              value={role || user.role || 'MEMBER'}
              onChange={(event) => {
                setRole(event.target.value);
                setRoleError('');
              }}
            >
              <option value="MEMBER">Member</option>
              <option value="ADMIN">Admin</option>
              <option value="CREATOR">Creator</option>
            </select>
          </div>
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--primary"
            onClick={saveRole}
            disabled={updateMutation.isPending || (role || user.role) === user.role}
          >
            {updateMutation.isPending ? 'Saving…' : 'Save role'}
          </button>
          {roleError && <p className="cortex-admin-form-error" role="alert">{roleError}</p>}
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
            {nextStatus === 'SUSPENDED' ? 'Suspend account' : 'Reactivate account'}
          </button>
          {updateError && <p className="cortex-admin-form-error" role="alert">{updateError.detail}</p>}
          {updateMutation.isSuccess && (
            <p className="cortex-admin-form-success">Account updated.</p>
          )}
        </section>
      </div>
    </div>
  );
}
