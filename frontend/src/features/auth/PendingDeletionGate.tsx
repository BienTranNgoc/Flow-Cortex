import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';
import { SessionStatus } from '../../app/SessionStatus';
import { useMeQuery } from './useAuth';

/**
 * Inside `RequireAuth` and `PasswordSetupGate`, outside `PhoneGate` so the deletion wall wins: an
 * account whose deletion is pending or past its deadline sees only `/account-deletion`. A failed
 * `/me` lets the page through; the API refuses the calls itself.
 */
export function PendingDeletionGate({ children }: Readonly<{ children: ReactNode }>) {
  const { t } = useTranslation();
  const me = useMeQuery();

  if (me.isPending) {
    return <SessionStatus>{t('auth.session.checking')}</SessionStatus>;
  }
  if (me.data?.accountDeletion) {
    return <Navigate to="/account-deletion" replace />;
  }
  return <>{children}</>;
}
