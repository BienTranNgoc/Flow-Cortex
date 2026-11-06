import { useTranslation } from 'react-i18next';
import { AuthLink } from './AuthLink';
import { PasswordResetGate, RequestResetForm } from './RequestResetForm';

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  return (
    <>
      <p className="auth-eyebrow">{t('auth.reset.eyebrow')}</p>
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.reset.forgot.title')}
      </h1>

      <PasswordResetGate>
        <p className="auth-subtitle">{t('auth.reset.forgot.intro')}</p>
        <RequestResetForm action={t('auth.reset.forgot.submit')} />
      </PasswordResetGate>

      <p className="auth-footnote">
        <AuthLink to="/login">{t('auth.reset.backToSignIn')}</AuthLink>
      </p>
    </>
  );
}
