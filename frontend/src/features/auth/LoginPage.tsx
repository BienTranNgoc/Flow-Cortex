import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { passwordResetEnabled } from '../../app/featureFlags';
import { OAuthButton } from '../../app/OAuthButton';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { googleAuthorizeUrl, oauthErrorKey } from './oauth';
import { activeLocale } from '../../lib/i18n';
import {
  accountLockedMessage,
  clearRememberedEmail,
  failureMessage,
  getRememberedEmail,
  setRememberedEmail,
  submitState,
  useAuthToken,
  useLoginMutation,
} from './useAuth';

export function LoginPage() {
  const { t } = useTranslation();
  const token = useAuthToken();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const loginMutation = useLoginMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const {
    register,
    handleSubmit,
    watch,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: getRememberedEmail(), password: '', rememberMe: true },
  });
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/';

  if (token) {
    return <Navigate to={returnTo} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    if (values.rememberMe) {
      setRememberedEmail(values.email);
    } else {
      clearRememberedEmail();
    }
    loginMutation.mutate(values, {
      onSuccess: () => {
        setSubmitSucceeded(true);
      },
    });
  });

  const oauthError = searchParams.get('oauth_error');
  const serverError =
    accountLockedMessage(loginMutation.error, t, activeLocale()) ??
    failureMessage(loginMutation, t) ??
    (oauthError ? t(oauthErrorKey(oauthError)) : undefined);
  const isPending = loginMutation.isPending;
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return t('auth.login.succeeded');
    if (isPending) return t('auth.login.submitting');
    return t('auth.login.submit');
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <h1 className="auth-card__title">{t('auth.login.title')}</h1>
        <p className="auth-subtitle">{t('auth.login.subtitle')}</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label={t('auth.login.google')}
            href={googleAuthorizeUrl(watch('rememberMe'), returnTo)}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>{t('auth.login.divider')}</span>
        </div>

        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('auth.login.formLabel')}
          className="auth-form"
        >
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="login-email">{t('auth.login.emailLabel')}</label>
            <input
              id="login-email"
              type="email"
              autoComplete="username"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email?.message && <p role="alert">{t(errors.email.message)}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="login-password">{t('auth.login.passwordLabel')}</label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password?.message && <p role="alert">{t(errors.password.message)}</p>}
          </div>
          <div className="login-meta-row">
            <label className="remember-row" htmlFor="login-remember">
              <input
                id="login-remember"
                type="checkbox"
                {...register('rememberMe')}
              />
              <span>{t('auth.login.rememberMe')}</span>
            </label>
            {passwordResetEnabled() && (
              <Link to="/forgot-password" className="forgot-password-link">
                {t('auth.login.forgotPassword')}
              </Link>
            )}
          </div>
          {serverError && (
            <p role="alert" className="form-error">
              {serverError}
            </p>
          )}
          <button
            className="primary-button"
            type="submit"
            data-state={dataState}
            disabled={isPending}
          >
            {buttonLabel}
          </button>
        </form>

        <p className="auth-footer">
          {t('auth.login.newHere')} <Link to="/register">{t('auth.login.createAccount')}</Link>
        </p>
      </section>
    </div>
  );
}
