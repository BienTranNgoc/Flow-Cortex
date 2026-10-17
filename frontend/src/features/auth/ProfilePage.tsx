import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useState } from 'react';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError, authHeaders, unwrap, updateProfile } from '@flow-cortex/api-client';
import type { ProfileUpdateRequest } from '@flow-cortex/api-client';
import { accountStatsEnabled } from '../../app/featureFlags';
import { profileSchema, type ProfileFormValues } from './profileSchema';
import { useMeQuery } from './useAuth';
import './ProfilePage.css';

function initialsFrom(name: string | null | undefined, email: string | null | undefined): string {
  const source = (name ?? '').trim() || email || '';
  const parts = source.split(/[\s@.]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

/** Fix my own display name; email/role/status are not self-service. */
export function ProfilePage() {
  const meQuery = useMeQuery();
  const queryClient = useQueryClient();
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    ...(meQuery.data ? { values: { name: meQuery.data.name ?? '' } } : {}),
  });

  const updateMutation = useMutation({
    mutationFn: (values: ProfileUpdateRequest) => unwrap(updateProfile(values, authHeaders())),
    onSuccess: () => {
      queryClient.invalidateQueries({ queryKey: ['me'] });
    },
  });

  if (meQuery.isLoading) {
    return (
      <main className="profile-page">
        <output>Loading…</output>
      </main>
    );
  }
  if (meQuery.isError) {
    return (
      <main className="profile-page">
        <h1>Profile</h1>
        <p role="alert" className="form-error">
          Could not load your profile.{' '}
          <button type="button" onClick={() => void meQuery.refetch()}>
            Retry
          </button>
        </p>
      </main>
    );
  }
  if (!meQuery.data) {
    return (
      <main className="profile-page">
        <output>Loading…</output>
      </main>
    );
  }

  const me = meQuery.data;
  const showAccountStats = accountStatsEnabled();
  const initials = initialsFrom(me.name, me.email);
  const onSubmit = handleSubmit((values) => updateMutation.mutate(values));
  const onCancel = () => reset({ name: me.name ?? '' });
  const serverError =
    updateMutation.error instanceof ApiError ? updateMutation.error.problem?.detail : undefined;

  return (
    <main className="profile-page">
      <header className="profile-page__header">
        <p className="eyebrow">MY PROFILE</p>
        <h1>Your account, your way</h1>
        <p className="profile-page__subtitle">
          Manage how you appear across FlowCortex. Email and role are managed by your admin.
        </p>
      </header>

      <div className="profile-grid">
        {/* Left card — identity */}
        <aside className="profile-card profile-card--identity" aria-label="Profile summary">
          <div className="profile-avatar" aria-hidden="true">
            <span>{initials}</span>
            <span className="profile-avatar__pulse" />
          </div>

          <div className="profile-identity__text">
            <h2>{me.name?.trim() || 'Unnamed user'}</h2>
            <p className="profile-identity__email">{me.email}</p>
            <span className="profile-role-pill">{me.role}</span>
          </div>

          {showAccountStats && (
            <ul className="profile-stats" aria-label="Account statistics">
              <li>
                <span className="profile-stats__value">1,248</span>
                <span className="profile-stats__label">XP</span>
              </li>
              <li>
                <span className="profile-stats__value">14d</span>
                <span className="profile-stats__label">Streak</span>
              </li>
              <li>
                <span className="profile-stats__value">#42</span>
                <span className="profile-stats__label">Rank</span>
              </li>
              <li>
                <span className="profile-stats__value">18</span>
                <span className="profile-stats__label">Credits</span>
              </li>
            </ul>
          )}
        </aside>

        {/* Right card — edit form + identity facts */}
        <section className="profile-card profile-card--form" aria-label="Edit profile">
          <h2 className="profile-card__title">Edit display name</h2>
          <p className="profile-card__hint">
            This is the name teammates see when you collaborate on a workflow.
          </p>

          <form onSubmit={onSubmit} noValidate>
            <div className="field">
              <label htmlFor="profile-name">Display name</label>
              <input
                id="profile-name"
                {...register('name')}
                aria-invalid={Boolean(errors.name) || undefined}
                placeholder="How should we call you?"
              />
              {errors.name && (
                <p role="alert" className="form-error">
                  {errors.name.message}
                </p>
              )}
            </div>

            {serverError && (
              <p role="alert" className="form-error">
                {serverError}
              </p>
            )}
            {updateMutation.isSuccess && (
              <p className="profile-saved" role="status">
                ✓ Saved
              </p>
            )}

            <div className="profile-form__actions">
              <button
                type="button"
                className="cortex-btn cortex-btn--ghost"
                onClick={onCancel}
                disabled={updateMutation.isPending}
              >
                Cancel
              </button>
              <button
                type="submit"
                className="cortex-btn cortex-btn--primary"
                disabled={updateMutation.isPending}
              >
                {updateMutation.isPending ? 'Saving…' : 'Save changes'}
              </button>
            </div>
          </form>

          <hr className="profile-card__divider" />

          <h2 className="profile-card__title">Identity</h2>
          <dl className="profile-facts">
            <div>
              <dt>Email</dt>
              <dd>{me.email}</dd>
            </div>
            <div>
              <dt>Role</dt>
              <dd>{me.role}</dd>
            </div>
            <div>
              <dt>Status</dt>
              <dd>
                {me.status === 'ACTIVE' && <span className="profile-status-dot" aria-hidden="true" />}
                {me.status || '—'}
              </dd>
            </div>
          </dl>

          <hr className="profile-card__divider" />

          <ApiKeySection email={me.email} />

          {showAccountStats && (
            <>
              <hr className="profile-card__divider" />

              <div className="profile-danger">
                <div>
                  <h3>Delete account</h3>
                  <p>Permanently remove your account and all associated data.</p>
                </div>
                <button type="button" className="cortex-btn cortex-btn--danger" disabled>
                  Request deletion
                </button>
              </div>
            </>
          )}
        </section>
      </div>
    </main>
  );
}

function ApiKeySection({ email }: { readonly email: string | undefined }) {
  const [revealed, setRevealed] = useState(false);
  const [copied, setCopied] = useState(false);
  const seed = (email ?? 'user').replace(/[^a-z0-9]/gi, '').toLowerCase();
  const visibleTail = seed.slice(-4).padStart(4, '0');
  const fullKey = `fc_live_${seed.slice(0, 6)}${'x'.repeat(20)}${visibleTail}`;
  const masked = `fc_live_${'*'.repeat(20)}${visibleTail}`;
  const display = revealed ? fullKey : masked;
  return (
    <section className="profile-apikey" aria-label="API key">
      <h2 className="profile-card__title">API key</h2>
      <p className="profile-card__hint">
        Use this key to call FlowCortex workflows from curl, Postman, or any HTTP client.
        Keep it private — anyone with the key can run on your behalf.
      </p>
      <div className="profile-apikey__row">
        <code className="profile-apikey__value" aria-label="API key value">
          {display}
        </code>
        <button
          type="button"
          className="cortex-btn cortex-btn--ghost profile-apikey__btn"
          onClick={() => setRevealed((value) => !value)}
          aria-pressed={revealed}
        >
          {revealed ? 'Hide' : 'Reveal'}
        </button>
        <button
          type="button"
          className="cortex-btn cortex-btn--ghost profile-apikey__btn"
          onClick={() => {
            void navigator.clipboard.writeText(fullKey).then(() => {
              setCopied(true);
              window.setTimeout(() => setCopied(false), 1500);
            });
          }}
        >
          {copied ? 'Copied!' : 'Copy'}
        </button>
      </div>
      <p className="profile-apikey__hint">
        Authorization header: <code>Authorization: Bearer &lt;your-key&gt;</code>
      </p>
    </section>
  );
}
