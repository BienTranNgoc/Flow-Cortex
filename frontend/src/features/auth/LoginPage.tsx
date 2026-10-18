import { zodResolver } from '@hookform/resolvers/zod';
import { useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { BrandPanel } from '../../app/BrandPanel';
import { passwordResetEnabled } from '../../app/featureFlags';
import { OAuthButton } from '../../app/OAuthButton';
import { ResendConfirmation } from './ConfirmEmailPage';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { googleAuthorizeUrl, oauthErrorMessage } from './oauth';
import {
  clearRememberedEmail,
  detailFrom,
  EMAIL_NOT_CONFIRMED,
  getRememberedEmail,
  problemCodeFrom,
  setRememberedEmail,
  submitState,
  useAuthToken,
  useLoginMutation,
} from './useAuth';

export function LoginPage() {
  const token = useAuthToken();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const loginMutation = useLoginMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const [showPassword, setShowPassword] = useState(false);
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
  const notConfirmed = problemCodeFrom(loginMutation.error) === EMAIL_NOT_CONFIRMED;
  const serverError = notConfirmed
    ? 'Confirm your email before signing in'
    : (detailFrom(loginMutation) ?? (oauthError ? oauthErrorMessage(oauthError) : undefined));
  const isPending = loginMutation.isPending;
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return '✓ Signed in';
    if (isPending) return 'Signing in…';
    return 'Sign in to Studio →';
  })();

  return (
    <div className="auth-page">
      <BrandPanel />

      <section className="auth-card auth-card--surface">
        <h1 className="auth-card__title">Sign in</h1>
        <p className="auth-subtitle">Access your creator workspace and run media pipelines.</p>

        <div className="oauth-row">
          <OAuthButton
            provider="google"
            label="Continue with Google"
            href={googleAuthorizeUrl(watch('rememberMe'), returnTo)}
          />
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
            <div className="field__password">
              <input
                id="login-password"
                type={showPassword ? 'text' : 'password'}
                autoComplete="current-password"
                aria-invalid={errors.password ? 'true' : 'false'}
                className="field__password-input"
                {...register('password')}
              />
              <button
                type="button"
                className="field__password-toggle"
                aria-label={showPassword ? 'Hide password' : 'Show password'}
                aria-pressed={showPassword}
                onClick={() => setShowPassword((value) => !value)}
              >
                <svg
                  className="field__password-icon"
                  viewBox="0 0 24 24"
                  width="20"
                  height="20"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                  strokeLinejoin="round"
                  aria-hidden="true"
                >
                  {showPassword ? (
                    <>
                      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
                      <circle cx="12" cy="12" r="3" />
                    </>
                  ) : (
                    <>
                      <path d="M17.94 17.94A10.94 10.94 0 0 1 12 19c-6.5 0-10-7-10-7a18.45 18.45 0 0 1 4.22-5.19" />
                      <path d="M9.9 4.24A10.94 10.94 0 0 1 12 4c6.5 0 10 7 10 7a18.5 18.5 0 0 1-2.16 3.19" />
                      <path d="M9.88 9.88a3 3 0 1 0 4.24 4.24" />
                      <line x1="2" y1="2" x2="22" y2="22" />
                    </>
                  )}
                </svg>
              </button>
            </div>
            {errors.password && <p role="alert">{errors.password.message}</p>}
          </div>
          <div className="login-meta-row">
            <label className="remember-row" htmlFor="login-remember">
              <input id="login-remember" type="checkbox" {...register('rememberMe')} />
              <span>Remember me</span>
            </label>
            {passwordResetEnabled() ? (
              <Link to="/forgot-password" className="forgot-password-link">
                Forgot password?
              </Link>
            ) : (
              <span
                className="forgot-password-link forgot-password-link--disabled"
                title="Forgot password flow is not yet enabled"
              >
                Forgot password?
              </span>
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

        {notConfirmed && loginMutation.variables && (
          <ResendConfirmation email={loginMutation.variables.email} />
        )}

        <p className="auth-footer">
          New to FlowCortex? <Link to="/register">Create a free account</Link>
        </p>
      </section>
    </div>
  );
}
