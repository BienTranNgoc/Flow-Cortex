import { Trans, useTranslation } from 'react-i18next';
import { logout } from '../features/auth/useAuth';
import { usePageTitle } from './usePageTitle';

/** Shown to a deactivated admin instead of the console: no admin call is made, only sign-out is offered. */
export function AdminDisabledPage({ email }: Readonly<{ email: string | undefined }>) {
  const { t } = useTranslation();
  usePageTitle(t('common.shell.disabled.title'));
  return (
    <main className="auth-page">
      <h1>{t('common.shell.disabled.title')}</h1>
      <p>
        {email ? (
          <Trans
            i18nKey="common.shell.disabled.messageWithEmail"
            values={{ email }}
            components={{ account: <strong /> }}
          />
        ) : (
          t('common.shell.disabled.message')
        )}
      </p>
      <button type="button" onClick={() => void logout()}>
        {t('common.shell.disabled.signOut')}
      </button>
    </main>
  );
}
