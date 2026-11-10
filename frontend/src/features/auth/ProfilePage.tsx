import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { accountStatsEnabled } from '../../app/featureFlags';
import { PageHeading } from '../../app/PageHeading';
import { useFeatures } from '../../app/useFeatures';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useBalance } from '../credits/useBalance';
import { AvatarPickerDialog } from '../profile/AvatarPickerDialog';
import { CoverPickerDialog } from '../profile/CoverPickerDialog';
import { useImageFailure } from '../profile/useImageFailure';
import { useProfileImagesQuery } from '../profile/useProfileImages';
import { roleLabel, statusLabel } from './enumLabels';
import { PhoneVerification } from './PhoneVerification';
import { PrivacySection } from './PrivacySection';
import { profileSchema, type ProfileFormValues } from './profileSchema';
import { UsernameField } from './UsernameField';
import { failureMessage, useMeQuery } from './useAuth';
import { useOwnProfileQuery, useUpdateNames } from './useOwnProfile';
import './ProfilePage.css';

const NAME_FIELDS = [
  { name: 'name', id: 'profile-name', label: 'nameLabel', placeholder: 'namePlaceholder' },
  {
    name: 'firstName',
    id: 'profile-first-name',
    label: 'firstNameLabel',
    placeholder: 'firstNamePlaceholder',
  },
  {
    name: 'lastName',
    id: 'profile-last-name',
    label: 'lastNameLabel',
    placeholder: 'lastNamePlaceholder',
  },
] as const;

function savedNames(
  displayName: string | null | undefined,
  own: { name?: string; firstName?: string; lastName?: string } | undefined,
): ProfileFormValues {
  return {
    name: own?.name ?? displayName ?? '',
    firstName: own?.firstName ?? '',
    lastName: own?.lastName ?? '',
  };
}

function initialsFrom(name: string | null | undefined, email: string | null | undefined): string {
  const source = (name ?? '').trim() || email || '';
  const parts = source.split(/[\s@.]+/).filter(Boolean);
  if (parts.length === 0) return '?';
  if (parts.length === 1) return parts[0]!.slice(0, 2).toUpperCase();
  return (parts[0]![0]! + parts[1]![0]!).toUpperCase();
}

/** My own names, username and privacy; email/role/status are not self-service. */
export function ProfilePage() {
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const ownProfile = useOwnProfileQuery();
  const { phoneVerification } = useFeatures();
  const credits = useBalance();
  const images = useProfileImagesQuery();
  const avatar = useImageFailure(images.data?.avatar?.url);
  const cover = useImageFailure(images.data?.cover?.url);
  const [picker, setPicker] = useState<'avatar' | 'cover' | null>(null);
  const {
    register,
    handleSubmit,
    formState: { errors },
    reset,
  } = useForm<ProfileFormValues>({
    resolver: zodResolver(profileSchema),
    ...(meQuery.data && ownProfile.data
      ? { values: savedNames(meQuery.data.name, ownProfile.data) }
      : {}),
  });

  const updateMutation = useUpdateNames();

  if (meQuery.isLoading) {
    return (
      <div className="profile-page">
        <output>{t('auth.profile.loading')}</output>
      </div>
    );
  }
  if (meQuery.isError) {
    return (
      <div className="profile-page">
        <PageHeading title={t('auth.profile.title')} />
        <p role="alert" className="form-error">
          {t('auth.profile.loadError')}{' '}
          <button type="button" onClick={() => void meQuery.refetch()}>
            {t('auth.profile.retry')}
          </button>
        </p>
      </div>
    );
  }
  if (!meQuery.data) {
    return (
      <div className="profile-page">
        <output>{t('auth.profile.loading')}</output>
      </div>
    );
  }

  const me = meQuery.data;
  const showAccountStats = accountStatsEnabled();
  const initials = initialsFrom(me.name, me.email);
  const onSubmit = handleSubmit((values) => updateMutation.mutate(values));
  const onCancel = () => reset(savedNames(me.name, ownProfile.data));
  const ownProfileError = ownProfile.isError && (
    <p role="alert" className="form-error">
      {t('auth.profile.loadError')}{' '}
      <button type="button" onClick={() => void ownProfile.refetch()}>
        {t('auth.profile.retry')}
      </button>
    </p>
  );
  const serverError = failureMessage(updateMutation, t);

  return (
    <div className="profile-page">
      <PageHeading title={t('auth.profile.headline')} lede={t('auth.profile.subtitle')} />

      <div className="profile-grid">
        {/* Left card — identity */}
        <aside
          className="profile-card profile-card--identity"
          aria-label={t('auth.profile.summaryLabel')}
        >
          <div className="profile-cover">
            {cover.shown && <img src={images.data?.cover?.url} alt="" onError={cover.onError} />}
          </div>

          <div className="profile-avatar" aria-hidden="true">
            {avatar.shown ? (
              <img src={images.data?.avatar?.url} alt="" onError={avatar.onError} />
            ) : (
              <span>{initials}</span>
            )}
          </div>

          <div className="profile-image-actions">
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={() => setPicker('avatar')}
            >
              {t('auth.profile.images.changePhoto')}
            </button>
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={() => setPicker('cover')}
            >
              {t('auth.profile.images.changeCover')}
            </button>
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
                    {formatNumber(credits.balance, activeLocale())}
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

          {!ownProfile.data && (ownProfileError || <output>{t('auth.profile.loading')}</output>)}
          {ownProfile.data && (
            <form onSubmit={onSubmit} noValidate>
              {NAME_FIELDS.map(({ name, id, label, placeholder }) => (
                <div className="field" key={name}>
                  <label htmlFor={id}>{t(`auth.profile.${label}`)}</label>
                  <input
                    id={id}
                    {...register(name)}
                    aria-invalid={Boolean(errors[name]) || undefined}
                    placeholder={t(`auth.profile.${placeholder}`)}
                  />
                  {errors[name]?.message && (
                    <p role="alert" className="form-error">
                      {t(errors[name].message)}
                    </p>
                  )}
                </div>
              ))}

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
          )}

          <hr className="profile-card__divider" />

          {ownProfile.data && (
            <UsernameField
              username={ownProfile.data.username}
              nextChangeAt={ownProfile.data.usernameNextChangeAt}
            />
          )}

          <hr className="profile-card__divider" />

          <PrivacySection />

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
                {me.status === 'ACTIVE' && (
                  <span className="profile-status-dot" aria-hidden="true" />
                )}
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

      <AvatarPickerDialog open={picker === 'avatar'} onClose={() => setPicker(null)} />
      <CoverPickerDialog open={picker === 'cover'} onClose={() => setPicker(null)} />
    </div>
  );
}
