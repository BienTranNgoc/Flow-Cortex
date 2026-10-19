import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { authHeaders, unwrap, updateProfile } from '@flow-cortex/api-client';
import type { ProfileUpdateRequest } from '@flow-cortex/api-client';
import { accountStatsEnabled } from '../../app/featureFlags';
import { useFeatures } from '../../app/useFeatures';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useBalance } from '../credits/useBalance';
import { roleLabel, statusLabel } from './enumLabels';
import { PhoneVerification } from './PhoneVerification';
import { profileSchema, type ProfileFormValues } from './profileSchema';
import { failureMessage, useMeQuery } from './useAuth';
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
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const { phoneVerification } = useFeatures();
  const credits = useBalance();
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
        <output>{t('auth.profile.loading')}</output>
      </main>
    );
  }
  if (meQuery.isError) {
    return (
      <main className="profile-page">
        <h1>{t('auth.profile.title')}</h1>
        <p role="alert" className="form-error">
          {t('auth.profile.loadError')}{' '}
          <button type="button" onClick={() => void meQuery.refetch()}>
            {t('auth.profile.retry')}
          </button>
        </p>
      </main>
    );
  }
  if (!meQuery.data) {
    return (
      <main className="profile-page">
        <output>{t('auth.profile.loading')}</output>
      </main>
    );
  }

  const me = meQuery.data;
  const showAccountStats = accountStatsEnabled();
  const initials = initialsFrom(me.name, me.email);
  const onSubmit = handleSubmit((values) => updateMutation.mutate(values));
  const onCancel = () => reset({ name: me.name ?? '' });
  const serverError = failureMessage(updateMutation, t);

  return (
    <main className="profile-page">
      <header className="profile-page__header">
        <p className="eyebrow">{t('auth.profile.eyebrow')}</p>
        <h1>{t('auth.profile.headline')}</h1>
        <p className="profile-page__subtitle">{t('auth.profile.subtitle')}</p>
      </header>

      <div className="profile-grid">
        {/* Left card — identity */}
        <aside
          className="profile-card profile-card--identity"
          aria-label={t('auth.profile.summaryLabel')}
        >
          <div className="profile-avatar" aria-hidden="true">
            <span>{initials}</span>
            <span className="profile-avatar__pulse" />
          </div>

          <div className="profile-identity__text">
            <h2>{me.name?.trim() || t('auth.profile.unnamed')}</h2>
            <p className="profile-identity__email">{me.email}</p>
            <span className="profile-role-pill">{roleLabel(t, me.role)}</span>
          </div>

          {(showAccountStats || credits !== undefined) && (
            <ul className="profile-stats" aria-label={t('auth.profile.statsLabel')}>
              {showAccountStats && (
                <>
                  <li>
                    <span className="profile-stats__value">
                      {formatNumber(1248, activeLocale())}
                    </span>
                    <span className="profile-stats__label">{t('auth.profile.stats.xp')}</span>
                  </li>
                  <li>
                    <span className="profile-stats__value">
                      {t('auth.profile.stats.streakValue', { days: 14 })}
                    </span>
                    <span className="profile-stats__label">{t('auth.profile.stats.streak')}</span>
                  </li>
                  <li>
                    <span className="profile-stats__value">#42</span>
                    <span className="profile-stats__label">{t('auth.profile.stats.rank')}</span>
                  </li>
                </>
              )}
              {credits !== undefined && (
                <li>
                  <span className="profile-stats__value">
                    {formatNumber(credits, activeLocale())}
                  </span>
                  <span className="profile-stats__label">{t('auth.profile.stats.credits')}</span>
                </li>
              )}
            </ul>
          )}
        </aside>

        {/* Right card — edit form + identity facts */}
        <section
          className="profile-card profile-card--form"
          aria-label={t('auth.profile.editLabel')}
        >
          <h2 className="profile-card__title">{t('auth.profile.editTitle')}</h2>
          <p className="profile-card__hint">{t('auth.profile.editHint')}</p>

          <form onSubmit={onSubmit} noValidate>
            <div className="field">
              <label htmlFor="profile-name">{t('auth.profile.nameLabel')}</label>
              <input
                id="profile-name"
                {...register('name')}
                aria-invalid={Boolean(errors.name) || undefined}
                placeholder={t('auth.profile.namePlaceholder')}
              />
              {errors.name?.message && (
                <p role="alert" className="form-error">
                  {t(errors.name.message)}
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
                {t('auth.profile.saved')}
              </p>
            )}

            <div className="profile-form__actions">
              <button
                type="button"
                className="cortex-btn cortex-btn--ghost"
                onClick={onCancel}
                disabled={updateMutation.isPending}
              >
                {t('auth.profile.cancel')}
              </button>
              <button
                type="submit"
                className="cortex-btn cortex-btn--primary"
                disabled={updateMutation.isPending}
              >
                {updateMutation.isPending ? t('auth.profile.saving') : t('auth.profile.save')}
              </button>
            </div>
          </form>

          <hr className="profile-card__divider" />

          <h2 className="profile-card__title">{t('auth.profile.identityTitle')}</h2>
          <dl className="profile-facts">
            <div>
              <dt>{t('auth.profile.emailLabel')}</dt>
              <dd>{me.email}</dd>
            </div>
            <div>
              <dt>{t('auth.profile.roleLabel')}</dt>
              <dd>{roleLabel(t, me.role)}</dd>
            </div>
            <div>
              <dt>{t('auth.profile.statusLabel')}</dt>
              <dd>
                {me.status === 'ACTIVE' && <span className="profile-status-dot" aria-hidden="true" />}
                {statusLabel(t, me.status) || '—'}
              </dd>
            </div>
          </dl>

          {phoneVerification && (
            <>
              <hr className="profile-card__divider" />
              <PhoneVerification />
            </>
          )}

          {showAccountStats && (
            <>
              <hr className="profile-card__divider" />

              <div className="profile-danger">
                <div>
                  <h3>{t('auth.profile.deleteTitle')}</h3>
                  <p>{t('auth.profile.deleteHint')}</p>
                </div>
                <button type="button" className="cortex-btn cortex-btn--danger" disabled>
                  {t('auth.profile.deleteRequest')}
                </button>
              </div>
            </>
          )}
        </section>
      </div>
    </main>
  );
}
