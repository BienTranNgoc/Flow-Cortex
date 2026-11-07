import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate } from 'react-router-dom';
import { SessionStatus } from '../../app/SessionStatus';
import { useMeQuery } from './useAuth';

/**
 * Inside `RequireAuth`: an account with no password and no Google sign-in has no way back in once
 * this session ends, so it is sent to `/set-password` until it has one. A failed `/me` lets the
 * page through; the page reports its own errors.
 */
export function PasswordSetupGate({ children }: Readonly<{ children: ReactNode }>) {
  const { t } = useTranslation();
  const me = useMeQuery();

  if (me.isPending) {
    return <SessionStatus>{t('auth.session.checking')}</SessionStatus>;
  }
  if (me.data?.passwordSetupRequired) {
    return <Navigate to="/set-password" replace />;
  }
  return <>{children}</>;
}
