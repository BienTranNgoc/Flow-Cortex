import type { ReactNode } from 'react';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router-dom';
import { useAuthToken, useMeQuery, useSessionReady } from '../features/auth/useAuth';
import { NoAccessPage } from './NoAccessPage';
import { can } from './permissions';
import { SessionStatus } from './SessionStatus';

/**
 * Entry guard of the whole console: no token → `/login` (remembering the page), a role without
 * `console.enter` → "No access". Nothing under it mounts, so a refused user triggers no admin call.
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
  if (!can(meQuery.data.role, 'console.enter')) {
    return <NoAccessPage email={meQuery.data.email} />;
  }
  return <>{children}</>;
}
