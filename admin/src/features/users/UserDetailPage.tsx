import { ApiError } from '@flow-cortex/api-client';
import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { z } from 'zod';
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
      <main>
        <h1>User not found</h1>
        <p className="list-error" role="alert">
          The user ID is not valid.
        </p>
        <Link className="table-link" to="/users">
          Back to users
        </Link>
      </main>
    );
  }

  if (userQuery.isLoading) {
    return (
      <main>
        <h1>User details</h1>
        <output>Loading user…</output>
      </main>
    );
  }

  if (userQuery.isError || !userQuery.data) {
    return (
      <main>
        <h1>User details</h1>
        <div className="list-error">
          <p role="alert">Could not load this user.</p>
          <button type="button" onClick={() => userQuery.refetch()}>
            Retry
          </button>
          <Link className="table-link" to="/users">
            Back to users
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
      setRoleError('Select a valid role.');
      return;
    }
    setRoleError('');
    updateMutation.mutate({ id: parsedId.data, changes: { role: parsedRole.data } });
  };

  return (
    <main className="user-detail-page">
      <Link className="back-link" to="/users">
        ← All users
      </Link>
      <div className="page-heading">
        <div className="user-profile-heading">
          <span className="user-avatar user-avatar-large" aria-hidden="true">
            {user.name?.trim().charAt(0).toUpperCase() || '?'}
          </span>
          <div>
            <p className="eyebrow">User account</p>
            <h1>{user.name || user.email}</h1>
            <p className="page-description">{user.email}</p>
          </div>
        </div>
        <span
          className={`status-badge ${user.status === 'ACTIVE' ? 'status-success' : 'status-warning'}`}
        >
          {user.status === 'SUSPENDED' ? 'Suspended' : 'Active'}
        </span>
      </div>

      <div className="detail-grid">
        <section className="detail-panel">
          <div className="section-heading">
            <div>
              <h2>Account information</h2>
              <p>Identity and access details</p>
            </div>
          </div>
          <dl className="user-meta-list">
            <div>
              <dt>User ID</dt>
              <dd>
                <code>{user.id}</code>
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
              <dd>{user.status === 'SUSPENDED' ? 'Suspended' : 'Active'}</dd>
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

        <section className="detail-panel">
          <div className="section-heading">
            <div>
              <h2>Access management</h2>
              <p>Changes are recorded without deleting account history.</p>
            </div>
          </div>
          <div className="field">
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
            onClick={saveRole}
            disabled={updateMutation.isPending || (role || user.role) === user.role}
          >
            {updateMutation.isPending ? 'Saving…' : 'Save role'}
          </button>
          {roleError && <p role="alert">{roleError}</p>}
          <button
            className={nextStatus === 'SUSPENDED' ? 'button-danger' : ''}
            type="button"
            onClick={() =>
              updateMutation.mutate({ id: parsedId.data, changes: { status: nextStatus } })
            }
            disabled={updateMutation.isPending}
          >
            {nextStatus === 'SUSPENDED' ? 'Suspend account' : 'Reactivate account'}
          </button>
          {updateError && <p role="alert">{updateError.detail}</p>}
          {updateMutation.isSuccess && (
            <p className="success-note" role="status">
              Account updated.
            </p>
          )}
        </section>
      </div>
    </main>
  );
}
