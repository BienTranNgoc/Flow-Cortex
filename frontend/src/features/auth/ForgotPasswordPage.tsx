import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { PasswordResetGate, RequestResetForm } from './RequestResetForm';

export function ForgotPasswordPage() {
  const { t } = useTranslation();
  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">{t('auth.reset.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.reset.forgot.title')}</h1>

        <PasswordResetGate>
          <p className="auth-subtitle">{t('auth.reset.forgot.intro')}</p>
          <RequestResetForm action={t('auth.reset.forgot.submit')} />
        </PasswordResetGate>

        <p className="auth-footer">
          <Link to="/login">{t('auth.reset.backToSignIn')}</Link>
        </p>
      </section>
    </div>
  );
}
