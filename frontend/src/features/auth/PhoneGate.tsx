import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router-dom';
import { SessionStatus } from '../../app/SessionStatus';
import { useMeQuery } from './useAuth';

/**
 * Inside `RequireAuth` and `PasswordSetupGate`: a member the server holds until a phone number is
 * verified is sent to `/verify-phone`, remembering the page they asked for. A failed `/me` lets the
 * page through; the API itself refuses the held member's calls and `queryClient` refetches `/me`.
 */
export function PhoneGate({ children }: Readonly<{ children: ReactNode }>) {
  const { t } = useTranslation();
  const location = useLocation();
  const me = useMeQuery();

  if (me.isPending) {
    return <SessionStatus>{t('auth.session.checking')}</SessionStatus>;
  }
  if (me.data?.phoneVerificationRequired) {
    return <Navigate to="/verify-phone" replace state={{ from: location }} />;
  }
  return <>{children}</>;
}
