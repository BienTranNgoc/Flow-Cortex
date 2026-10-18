import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { Link, Navigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { googleAuthorizeUrl } from './oauth';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import {
  failureMessage,
  submitState,
  useAuthToken,
  useRegisterMutation,
} from './useAuth';

export function RegisterPage() {
  const { t } = useTranslation();
  const token = useAuthToken();
  const registerMutation = useRegisterMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<RegisterFormValues>({ resolver: zodResolver(registerSchema) });

  if (token) {
    return <Navigate to="/" replace />;
  }

  const onSubmit = handleSubmit(({ confirmPassword: _confirmPassword, ...values }) => {
    registerMutation.mutate(values, {
      onSuccess: () => {
        setSubmitSucceeded(true);
      },
    });
  });

  const serverError = failureMessage(registerMutation, t);
  const isPending = registerMutation.isPending;
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return t('auth.register.succeeded');
    if (isPending) return t('auth.register.submitting');
    return t('auth.register.submit');
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <p className="eyebrow">{t('auth.register.eyebrow')}</p>
        <h1 className="auth-card__title">{t('auth.register.title')}</h1>
        <p className="auth-subtitle">{t('auth.register.subtitle')}</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label={t('auth.register.google')}
            href={googleAuthorizeUrl(true, '/')}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>{t('auth.register.divider')}</span>
        </div>

        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('auth.register.formLabel')}
          className="auth-form"
        >
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="register-name">{t('auth.register.nameLabel')}</label>
            <input
              id="register-name"
              autoComplete="name"
              aria-invalid={errors.name ? 'true' : 'false'}
              {...register('name')}
            />
            {errors.name?.message && <p role="alert">{t(errors.name.message)}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="register-email">{t('auth.register.emailLabel')}</label>
            <input
              id="register-email"
              type="email"
              autoComplete="email"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email?.message && <p role="alert">{t(errors.email.message)}</p>}
          </div>
          <div className="field" style={{ '--i': 2 } as React.CSSProperties}>
            <label htmlFor="register-password">{t('auth.register.passwordLabel')}</label>
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password?.message && <p role="alert">{t(errors.password.message)}</p>}
          </div>
          <div className="field" style={{ '--i': 3 } as React.CSSProperties}>
            <label htmlFor="register-confirm-password">
              {t('auth.register.confirmPasswordLabel')}
            </label>
            <input
              id="register-confirm-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.confirmPassword ? 'true' : 'false'}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword?.message && (
              <p role="alert">{t(errors.confirmPassword.message)}</p>
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
          {t('auth.register.haveAccount')} <Link to="/login">{t('auth.register.signIn')}</Link>
        </p>
      </section>
    </div>
  );
}
