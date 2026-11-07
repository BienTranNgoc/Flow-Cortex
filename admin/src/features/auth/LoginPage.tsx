import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { NodiMark } from '@flow-cortex/ui-kit';
import { ArrowRight, CircleNotch, WarningCircle } from '@phosphor-icons/react';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { NODI_WORDMARK } from '../../app/brand';
import { usePageTitle } from '../../app/usePageTitle';
import { AppLanguageToggle } from '../../lib/AppLanguageToggle';
import { activeLocale } from '../../lib/i18n';
import { accountLockedMessage, useAuthToken, useLoginMutation } from './useAuth';

const PITCH_POINT_KEYS = [
  'auth.login.pitch.runs',
  'auth.login.pitch.mediaJobs',
  'auth.login.pitch.accounts',
] as const;

/** Email+password → token; once a token is stored, returns to the page that sent the visitor here. */
export function LoginPage() {
  const { t } = useTranslation();
  usePageTitle(t('auth.login.title'));
  const token = useAuthToken();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({ resolver: zodResolver(loginSchema) });

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/runs';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => loginMutation.mutate(values));

  const serverDetail =
    loginMutation.error instanceof ApiError ? loginMutation.error.problem?.detail : undefined;
  const serverError = loginMutation.error
    ? (accountLockedMessage(loginMutation.error, t, activeLocale()) ??
      (serverDetail || t('common.errors.requestFailed')))
    : undefined;

  return (
    <main className="auth-shell">
      <div className="auth-shell__language">
        <AppLanguageToggle />
      </div>
      <section className="auth-shell__card">
        <div className="auth-shell__brand">
          <span className="auth-shell__brand-mark" aria-hidden="true">
            <NodiMark size={32} />
          </span>
          <div className="auth-shell__brand-text">
            <strong>{NODI_WORDMARK}</strong>
            <span>{t('common.header.console')}</span>
          </div>
        </div>
        <div className="auth-shell__form-inner">
          <header className="auth-shell__form-header">
            <span className="auth-shell__eyebrow">{t('auth.login.eyebrow')}</span>
            <h1>{t('auth.login.title')}</h1>
            <p>{t('auth.login.subtitle')}</p>
          </header>

          <form
            onSubmit={onSubmit}
            noValidate
            aria-label={t('auth.login.title')}
            className="auth-form"
          >
            <div className="auth-form__field">
              <label htmlFor="login-email" className="auth-form__label">
                {t('auth.login.email')}
              </label>
              <input
                id="login-email"
                type="email"
                autoComplete="username"
                className={`auth-form__input${errors.email ? ' is-invalid' : ''}`}
                placeholder={t('auth.login.emailPlaceholder')}
                {...register('email')}
              />
              {errors.email?.message && (
                <p className="auth-form__error" role="alert">
                  <WarningCircle aria-hidden="true" size={14} />
                  {t(errors.email.message)}
                </p>
              )}
            </div>

            <div className="auth-form__field">
              <label htmlFor="login-password" className="auth-form__label">
                {t('auth.login.password')}
              </label>
              <input
                id="login-password"
                type="password"
                autoComplete="current-password"
                className={`auth-form__input${errors.password ? ' is-invalid' : ''}`}
                placeholder="••••••••"
                {...register('password')}
              />
              {errors.password?.message && (
                <p className="auth-form__error" role="alert">
                  <WarningCircle aria-hidden="true" size={14} />
                  {t(errors.password.message)}
                </p>
              )}
            </div>

            {serverError && (
              <p className="auth-form__error auth-form__error--server" role="alert">
                <WarningCircle aria-hidden="true" size={14} />
                {serverError}
              </p>
            )}

            <button
              type="submit"
              className="auth-form__submit"
              disabled={loginMutation.isPending}
            >
              {loginMutation.isPending ? (
                <>
                  <CircleNotch aria-hidden="true" size={16} className="auth-form__spinner" />
                  {t('auth.login.submitting')}
                </>
              ) : (
                <>
                  {t('auth.login.submit')}
                  <ArrowRight aria-hidden="true" size={16} />
                </>
              )}
            </button>
          </form>

          <footer className="auth-shell__form-foot">
            <span>{t('auth.login.needAccess')}</span>
            <a href="mailto:ops@flow-cortex.app">{t('auth.login.contactOwner')}</a>
          </footer>
        </div>
      </section>

      <aside className="auth-shell__pitch">
        <h2>{t('auth.login.pitch.title')}</h2>
        <p>{t('auth.login.pitch.lede')}</p>
        <ul className="auth-shell__pitch-list">
          {PITCH_POINT_KEYS.map((key) => (
            <li key={key}>
              <span className="auth-shell__pitch-dot" aria-hidden="true" /> {t(key)}
            </li>
          ))}
        </ul>
        <p className="auth-shell__aside-foot">{t('auth.login.asideFoot')}</p>
      </aside>
    </main>
  );
}
