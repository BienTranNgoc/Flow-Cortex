import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { OAuthButton } from '../../app/OAuthButton';
import { loginSchema, type LoginFormValues } from './loginSchema';
import {
  clearRememberedEmail,
  detailFrom,
  getRememberedEmail,
  setRememberedEmail,
  useAuthToken,
  useLoginMutation,
  type LoginVariables,
} from './useAuth';

export function LoginPage() {
  const token = useAuthToken();
  const location = useLocation();
  const loginMutation = useLoginMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const {
    register,
    handleSubmit,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: getRememberedEmail(), password: '', rememberMe: true },
  });

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/';
    return <Navigate to={from} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    if (values.rememberMe) {
      setRememberedEmail(values.email);
    } else {
      clearRememberedEmail();
    }
    loginMutation.mutate(values as unknown as LoginVariables, {
      onSuccess: () => {
        setSubmitSucceeded(true);
      },
    });
  });

  const serverError = detailFrom(loginMutation);
  const isPending = loginMutation.isPending;
  const dataState = submitSucceeded ? 'success' : isPending ? 'pending' : 'idle';

  const buttonLabel = (() => {
    if (submitSucceeded) return '✓ Signed in';
    if (isPending) return 'Signing in…';
    return 'Sign in to Studio →';
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card">
        <h1 className="auth-card__title">Sign in</h1>
        <p className="auth-subtitle">Access your creator workspace and run media pipelines.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Continue with Google"
            onSelect={(p) => window.console.info(`[ui-only] oauth: ${p}`)}
          />
        </div>

        <div className="auth-divider" aria-hidden="true">
          <span>or sign in with email</span>
        </div>

        <form onSubmit={onSubmit} noValidate aria-label="Sign in" className="auth-form">
          <div className="field" style={{ '--i': 0 } as React.CSSProperties}>
            <label htmlFor="login-email">Email</label>
            <input
              id="login-email"
              type="email"
              autoComplete="username"
              aria-invalid={errors.email ? 'true' : 'false'}
              {...register('email')}
            />
            {errors.email && <p role="alert">{errors.email.message}</p>}
          </div>
          <div className="field" style={{ '--i': 1 } as React.CSSProperties}>
            <label htmlFor="login-password">Password</label>
            <input
              id="login-password"
              type="password"
              autoComplete="current-password"
              aria-invalid={errors.password ? 'true' : 'false'}
              {...register('password')}
            />
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <label className="remember-row" htmlFor="login-remember">
            <input
              id="login-remember"
              type="checkbox"
              {...register('rememberMe')}
            />
            <span>Remember me on this device</span>
          </label>
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
          New to Flow-Cortex? <Link to="/register">Create a free account</Link>
        </p>
      </section>
    </div>
  );
}
