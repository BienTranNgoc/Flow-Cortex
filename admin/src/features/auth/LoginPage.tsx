import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Navigate, useLocation } from 'react-router-dom';
import { ApiError } from '@flow-cortex/api-client';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { usePageTitle } from '../../app/usePageTitle';
import { AppLanguageToggle } from '../../lib/AppLanguageToggle';
import { activeLocale } from '../../lib/i18n';
import { accountLockedMessage, useAuthToken, useLoginMutation } from './useAuth';

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
    <main className="auth-page">
      <header className="auth-page__language">
        <AppLanguageToggle />
      </header>
      <form onSubmit={onSubmit} noValidate aria-label={t('auth.login.title')}>
        <h1>{t('auth.login.title')}</h1>
        <p>{t('auth.login.subtitle')}</p>
        <div className="field">
          <label htmlFor="login-email">{t('auth.login.email')}</label>
          <input id="login-email" type="email" autoComplete="username" {...register('email')} />
          {errors.email?.message && <p role="alert">{t(errors.email.message)}</p>}
        </div>
        <div className="field">
          <label htmlFor="login-password">{t('auth.login.password')}</label>
          <input
            id="login-password"
            type="password"
            autoComplete="current-password"
            {...register('password')}
          />
          {errors.password?.message && <p role="alert">{t(errors.password.message)}</p>}
        </div>
        {serverError && (
          <p role="alert" className="form-error">
            {serverError}
          </p>
        )}
        <button type="submit" disabled={loginMutation.isPending}>
          {loginMutation.isPending ? t('auth.login.submitting') : t('auth.login.submit')}
        </button>
      </form>
    </main>
  );
}
