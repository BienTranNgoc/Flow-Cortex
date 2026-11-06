import { Trans, useTranslation } from 'react-i18next';
import { logout } from '../features/auth/useAuth';
import { usePageTitle } from './usePageTitle';

export function NoAccessPage({ email }: Readonly<{ email: string | undefined }>) {
  const { t } = useTranslation();
  usePageTitle(t('common.shell.noAccess.title'));
  return (
    <main className="auth-page">
      <h1>{t('common.shell.noAccess.title')}</h1>
      <p>
        {email ? (
          <Trans
            i18nKey="common.shell.noAccess.messageWithEmail"
            values={{ email }}
            components={{ account: <strong /> }}
          />
        ) : (
          t('common.shell.noAccess.message')
        )}
      </p>
      <button type="button" onClick={() => void logout()}>
        {t('common.shell.noAccess.signOut')}
      </button>
    </main>
  );
}
