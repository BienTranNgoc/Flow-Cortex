import { useEffect, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useNavigate } from 'react-router-dom';
import { AccountDeletionViewState } from '@flow-cortex/api-client';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { daysLeft } from './pendingDeletion';
import { logout, useMeQuery } from './useAuth';
import { deletionFailure, useCancelAccountDeletion } from './useAccountDeletion';
import './AccountDeletion.css';

const DEADLINE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium', timeStyle: 'short' };

function cancelFailureKey(error: unknown) {
  switch (deletionFailure(error).kind) {
    case 'deadlinePassed':
      return 'auth.accountDeletion.pending.deadlinePassed';
    case 'notPending':
      return 'auth.accountDeletion.pending.notPending';
    default:
      return 'auth.accountDeletion.pending.cancelFailed';
  }
}

/**
 * The only screen a member whose account is scheduled for deletion can use: the deadline, the days
 * left and cancel while `PENDING`; the passed deadline without cancel while `EXPIRED`. A member with
 * no request is sent home, which is where a successful cancel ends.
 */
export function PendingDeletionPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const me = useMeQuery();
  const cancel = useCancelAccountDeletion();
  const headingRef = useRef<HTMLHeadingElement>(null);
  const [signingOut, setSigningOut] = useState(false);
  const view = me.data?.accountDeletion;
  const state = view?.state;

  useEffect(() => {
    if (state) headingRef.current?.focus();
  }, [state]);

  if (me.isPending) {
    return (
      <p role="status" className="auth-status">
        {t('auth.session.checking')}
      </p>
    );
  }
  if (me.isError && !me.data) {
    return (
      <div className="pending-deletion">
        <p role="alert" className="auth-alert">
          {t('auth.accountDeletion.pending.loadError')}
        </p>
        <button type="button" className="auth-btn auth-btn--secondary" onClick={() => void me.refetch()}>
          {t('auth.accountDeletion.pending.retry')}
        </button>
      </div>
    );
  }
  if (!view) {
    return <Navigate to="/" replace />;
  }

  // The login page sends a signed-in visitor away, so it opens only once the session is gone.
  const onSignOut = () => {
    setSigningOut(true);
    void logout().then(() => navigate('/login', { replace: true }));
  };
  const expired = view.state === AccountDeletionViewState.EXPIRED;
  const deadline = formatDateTime(view.scheduledAt, activeLocale(), DEADLINE_FORMAT);
  const failureText = cancel.isError ? t(cancelFailureKey(cancel.error)) : null;

  return (
    <div className="pending-deletion">
      <h1 ref={headingRef} className="auth-title" tabIndex={-1}>
        {expired ? t('auth.accountDeletion.expired.title') : t('auth.accountDeletion.pending.title')}
      </h1>
      {expired ? (
        <>
          <p className="auth-subtitle">{t('auth.accountDeletion.expired.body', { deadline })}</p>
          <p className="pending-deletion__notice">{t('auth.accountDeletion.expired.signedOut')}</p>
        </>
      ) : (
        <>
          <p className="auth-subtitle">{t('auth.accountDeletion.pending.deadline', { deadline })}</p>
          <p className="pending-deletion__days">
            {t('auth.accountDeletion.pending.daysLeft', { count: daysLeft(view.scheduledAt, new Date()) })}
          </p>
          <p className="pending-deletion__notice">{t('auth.accountDeletion.pending.signedOut')}</p>
          {failureText && (
            <p role="alert" className="auth-alert">
              {failureText}
            </p>
          )}
          <button
            type="button"
            className="auth-btn auth-btn--primary"
            onClick={() => cancel.mutate()}
            disabled={cancel.isPending}
            aria-busy={cancel.isPending}
          >
            {cancel.isPending
              ? t('auth.accountDeletion.pending.cancelling')
              : t('auth.accountDeletion.pending.cancel')}
          </button>
        </>
      )}
      <button
        type="button"
        className="auth-btn auth-btn--secondary"
        onClick={onSignOut}
        disabled={signingOut}
        aria-busy={signingOut}
      >
        {t('auth.accountDeletion.pending.signOut')}
      </button>
    </div>
  );
}
