import { useEffect, useRef, type ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router-dom';
import { TwoFactorSetupPage } from '../features/auth/TwoFactorSetupPage';
import { logout, useAuthToken, useMeQuery, useSessionReady } from '../features/auth/useAuth';
import { AdminDisabledPage } from './AdminDisabledPage';
import { NoAccessPage } from './NoAccessPage';
import { canEnterConsole } from './permissions';
import { SessionStatus } from './SessionStatus';
import { totpGate } from './totpGate';

/**
 * Entry guard of the whole console: no token → `/login` (remembering the page), a deactivated admin
 * → "deactivated", an account that must have a second factor → setup or a fresh sign-in through the
 * code step, an account holding no key → "No access". Nothing under it mounts, so a refused user
 * triggers no admin call.
 */
export function RequireAdminApp({ children }: Readonly<{ children: ReactNode }>) {
  const token = useAuthToken();
  const sessionReady = useSessionReady();
  const location = useLocation();
  const meQuery = useMeQuery();
  const { t } = useTranslation();

  if (!sessionReady) {
    return <SessionStatus>{t('common.shell.session.checkingSession')}</SessionStatus>;
  }
  if (!token) {
    return <Navigate to="/login" replace state={{ from: location.pathname }} />;
  }
  if (meQuery.isPending) {
    return <SessionStatus>{t('common.shell.session.checkingAccount')}</SessionStatus>;
  }
  if (meQuery.isError) {
    return (
      <main className="auth-page">
        <p role="alert">{t('common.shell.session.accountError')}</p>
        <button type="button" onClick={() => meQuery.refetch()}>
          {t('common.shell.session.retry')}
        </button>
      </main>
    );
  }
  if (meQuery.data.adminDisabled) {
    return <AdminDisabledPage email={meQuery.data.email} />;
  }
  const gate = totpGate(meQuery.data);
  if (gate === 'setup') {
    return <TwoFactorSetupPage email={meQuery.data.email} />;
  }
  if (gate === 'signOut') {
    return <SignOutForCode />;
  }
  if (!canEnterConsole(meQuery.data)) {
    return <NoAccessPage email={meQuery.data.email} />;
  }
  return <>{children}</>;
}

/**
 * An enrolled account whose session never passed a code (it predates the enrollment, or came from a
 * path that skipped the challenge) signs out once, so its next sign-in goes through the code step.
 */
function SignOutForCode() {
  const { t } = useTranslation();
  const started = useRef(false);
  useEffect(() => {
    if (started.current) return;
    started.current = true;
    void logout();
  }, []);
  return <SessionStatus>{t('auth.totp.signingOut')}</SessionStatus>;
}
