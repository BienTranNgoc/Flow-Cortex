import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import type { PrivacySettings } from '@flow-cortex/api-client';
import { failureMessage } from './useAuth';
import { useAccountPreferencesQuery, useSavePrivacy } from './useAccountPreferences';

type Field = keyof PrivacySettings;

const FAILED_KEY = 'auth.profile.privacy.failed';

const AUDIENCE = ['everyone', 'followers', 'only_me'] as const;
const STATS = ['show', 'hide'] as const;
const FIELDS: ReadonlyArray<{ key: Field; options: readonly string[]; fallback: string }> = [
  { key: 'profile', options: AUDIENCE, fallback: 'everyone' },
  { key: 'activity', options: AUDIENCE, fallback: 'everyone' },
  { key: 'message', options: AUDIENCE, fallback: 'everyone' },
  { key: 'stats', options: STATS, fallback: 'show' },
];

export function PrivacySection() {
  const { t } = useTranslation();
  const query = useAccountPreferencesQuery();
  const save = useSavePrivacy();
  // The value just picked, shown until its save settles; a failed save drops it so the saved one returns.
  const [optimistic, setOptimistic] = useState<Partial<Record<Field, string>>>({});

  const settle = (field: Field) =>
    setOptimistic((current) => {
      const { [field]: _settled, ...rest } = current;
      return rest;
    });

  const choose = (field: Field, value: string) => {
    setOptimistic((current) => ({ ...current, [field]: value }));
    save.mutate({ [field]: value } as Partial<PrivacySettings>, { onSettled: () => settle(field) });
  };

  const saved = query.data?.privacy;

  return (
    <section aria-labelledby="profile-privacy-title" className="profile-privacy">
      <h2 id="profile-privacy-title" className="profile-card__title">
        {t('auth.profile.privacy.title')}
      </h2>
      <p className="profile-card__hint">{t('auth.profile.privacy.hint')}</p>

      <div className="profile-privacy__grid">
        {FIELDS.map(({ key, options, fallback }) => {
          const id = `profile-privacy-${key}`;
          const value = optimistic[key] ?? saved?.[key] ?? fallback;
          return (
            <div className="field" key={key}>
              <label htmlFor={id}>{t(`auth.profile.privacy.${key}.label`)}</label>
              <select
                id={id}
                value={value}
                disabled={!saved}
                onChange={(event) => choose(key, event.target.value)}
              >
                {options.map((option) => (
                  <option key={option} value={option}>
                    {t(`auth.profile.privacy.option.${option}`)}
                  </option>
                ))}
              </select>
            </div>
          );
        })}
      </div>

      {save.isSuccess && (
        <p className="profile-saved" role="status">
          {t('auth.profile.privacy.saved')}
        </p>
      )}
      {save.isError && (
        <p role="alert" className="form-error">
          {failureMessage(save, t, FAILED_KEY)}
        </p>
      )}
    </section>
  );
}
