import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { AccountDeletionDialog } from './AccountDeletionDialog';
import { useAccountDeletionStatus } from './useAccountDeletion';

/**
 * The delete-account block of the profile page. It appears only while the server reports deletion
 * as switched on; while that is unknown (loading, failed) the page shows nothing rather than a
 * button that may be refused.
 */
export function AccountDeletionZone() {
  const { t } = useTranslation();
  const status = useAccountDeletionStatus();
  const [open, setOpen] = useState(false);

  if (!status.data?.enabled) return null;

  return (
    <>
      <hr className="profile-card__divider" />
      <div className="profile-danger">
        <div>
          <h3>{t('auth.accountDeletion.zone.title')}</h3>
          <p>{t('auth.accountDeletion.zone.hint', { days: status.data.gracePeriodDays })}</p>
        </div>
        <button type="button" className="cortex-btn cortex-btn--danger" onClick={() => setOpen(true)}>
          {t('auth.accountDeletion.zone.request')}
        </button>
      </div>
      <AccountDeletionDialog
        open={open}
        gracePeriodDays={status.data.gracePeriodDays}
        onClose={() => setOpen(false)}
      />
    </>
  );
}
