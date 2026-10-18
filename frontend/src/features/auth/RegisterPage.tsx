import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { googleAuthorizeUrl } from './oauth';
import { registerSchema, type RegisterFormValues } from './registerSchema';
import {
  detailFrom,
  submitState,
  useAuthToken,
  useRegisterMutation,
} from './useAuth';

export function RegisterPage() {
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

  const serverError = detailFrom(registerMutation);
  const isPending = registerMutation.isPending;
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return '✓ Account created';
    if (isPending) return 'Creating account…';
    return 'Get started free →';
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card auth-card--surface">
        <h1 className="auth-card__title">Create your account</h1>
        <p className="auth-subtitle">Start generating visual workflows in minutes.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Sign up with Google"
            href={googleAuthorizeUrl(true, '/')}
          />
        </div>

        <form onSubmit={onSubmit} noValidate aria-label="Create account" className="auth-form">
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="register-name">Full name</label>
            <input
              id="register-name"
              autoComplete="name"
              aria-invalid={errors.name ? 'true' : 'false'}
              {...register('name')}
            />
            {errors.name && <p role="alert">{errors.name.message}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="register-email">Work or personal email</label>
            <input
              id="register-email"
              type="email"
              autoComplete="email"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field" style={{ '--i': 2 } as React.CSSProperties}>
            <label htmlFor="register-password">Password</label>
            <input
              id="register-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <div className="field" style={{ '--i': 3 } as React.CSSProperties}>
            <label htmlFor="register-confirm-password">Confirm password</label>
            <input
              id="register-confirm-password"
              type="password"
              autoComplete="new-password"
              aria-invalid={errors.confirmPassword ? 'true' : 'false'}
              {...register('confirmPassword')}
            />
            {errors.confirmPassword && (
              <p role="alert">{errors.confirmPassword.message}</p>
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
          Already have an account? <Link to="/login">Sign in to workspace</Link>
        </p>
      </section>
    </div>
  );
}
