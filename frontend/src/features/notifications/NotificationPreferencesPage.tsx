import { useId } from 'react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { PageHeading } from '../../app/PageHeading';
import { Skeleton } from '../../app/Skeleton';
import { StatusBlock } from '../../app/StatusBlock';
import {
  PREFERENCE_FIELDS,
  usePreferencesQuery,
  useUpdatePreferences,
  type PreferenceField,
} from './usePreferences';
import './NotificationPreferencesPage.css';

/** The member's in-app and email choices; each change is saved as soon as it is made. */
export function NotificationPreferencesPage() {
  const { t } = useTranslation();
  const idBase = useId();
  const query = usePreferencesQuery();
  const update = useUpdatePreferences();

  return (
    <div className="notification-preferences">
      <PageHeading
        title={t('notifications.preferences.title')}
        lede={t('notifications.preferences.lede')}
        actions={
          <Link to="/notifications" className="action-link">
            {t('notifications.preferences.back')}
          </Link>
        }
      />

      {query.isLoading && <Skeleton shape="row" count={3} />}
      {query.isError && !query.data && (
        <StatusBlock
          kind="error"
          message={t('notifications.preferences.loadError')}
          action={{ label: t('notifications.retry'), onClick: () => void query.refetch() }}
        />
      )}
      {query.data && (
        <ul className="notification-preferences__list">
          {PREFERENCE_FIELDS.map((field: PreferenceField) => {
            const labelId = `${idBase}-${field}-label`;
            const descriptionId = `${idBase}-${field}-description`;
            const checked = query.data[field] ?? true;
            return (
              <li key={field} className="notification-preferences__item">
                <div className="notification-preferences__text">
                  <span id={labelId} className="notification-preferences__label">
                    {t(`notifications.preferences.${field}.label`)}
                  </span>
                  <span id={descriptionId} className="notification-preferences__description">
                    {t(`notifications.preferences.${field}.description`)}
                  </span>
                </div>
                <button
                  type="button"
                  role="switch"
                  aria-checked={checked}
                  aria-labelledby={labelId}
                  aria-describedby={descriptionId}
                  className="notification-preferences__switch"
                  onClick={() => update.mutate({ [field]: !checked })}
                >
                  <span className="notification-preferences__thumb" aria-hidden="true" />
                </button>
              </li>
            );
          })}
        </ul>
      )}

      <p className="notification-preferences__note">{t('notifications.preferences.alwaysSent')}</p>
      {update.isError && (
        <p role="alert" className="form-error">
          {t('notifications.preferences.saveError')}
        </p>
      )}
    </div>
  );
}
