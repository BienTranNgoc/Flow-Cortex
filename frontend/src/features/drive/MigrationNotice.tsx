import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { useMeQuery } from '../auth/useAuth';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { dismissNotice, noticeVisible, type NoticeStorage } from './model/migrationNotice';
import { useDriveMigration } from './useDriveMigration';
import './shared/DriveShared.css';

// Reading `window.localStorage` itself can throw, so the access stays inside the guarded calls.
const browserStorage: NoticeStorage = {
  getItem: (key) => window.localStorage.getItem(key),
  setItem: (key, value) => window.localStorage.setItem(key, value),
};

/** Tells a member once, per device, that earlier runs and results were moved into Drive. */
export function MigrationNotice() {
  const { t } = useTranslation();
  const migration = useDriveMigration();
  const me = useMeQuery();
  const [dismissedForVisit, setDismissedForVisit] = useState(false);

  const userId = me.data?.id;
  const record = migration.data;
  if (!record || !userId || dismissedForVisit) return null;
  if (!noticeVisible(record, userId, browserStorage)) return null;

  const locale = activeLocale();
  const runs = record.counts?.runs ?? 0;
  const results = record.counts?.results ?? 0;
  const body =
    runs + results === 0
      ? t('drive.notice.bodyNoCounts')
      : t('drive.notice.body', {
          runs: t('drive.notice.runs', { count: runs, n: formatNumber(runs, locale) }),
          results: t('drive.notice.results', { count: results, n: formatNumber(results, locale) }),
        });

  return (
    <div className="drive-notice" role="status">
      <div className="drive-notice__text">
        <p className="drive-notice__title">{t('drive.notice.title')}</p>
        <p className="drive-notice__body">{body}</p>
      </div>
      <button
        type="button"
        className="drive-button"
        onClick={() => {
          dismissNotice(record, userId, browserStorage);
          setDismissedForVisit(true);
        }}
      >
        {t('drive.notice.dismiss')}
      </button>
    </div>
  );
}
