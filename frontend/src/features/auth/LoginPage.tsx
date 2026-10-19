import { ApiError } from '@flow-cortex/api-client';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Link, Navigate, useLocation, useSearchParams } from 'react-router-dom';
import { oauthEnabled, passwordResetEnabled } from '../../app/featureFlags';
import { OAuthButton } from '../../app/OAuthButton';
import { AuthLink } from './AuthLink';
import { authCopy } from './authCopy';
import { useAuthPose } from './authPose';
import { ResendConfirmation } from './ConfirmEmailPage';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { nodiPeekState } from './nodiPeekState';
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

const eyeIcon = (
  <svg
    viewBox="0 0 24 24"
    width="20"
    height="20"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M2 12s3.6-7 10-7 10 7 10 7-3.6 7-10 7S2 12 2 12z" />
    <circle cx="12" cy="12" r="3" />
  </svg>
);

const eyeOffIcon = (
  <svg
    viewBox="0 0 24 24"
    width="20"
    height="20"
    fill="none"
    stroke="currentColor"
    strokeWidth="2"
    strokeLinecap="round"
    strokeLinejoin="round"
    aria-hidden="true"
  >
    <path d="M10.6 5.1A10.8 10.8 0 0 1 12 5c6.4 0 10 7 10 7a17.6 17.6 0 0 1-3.2 4.2M6.6 6.6C3.8 8.4 2 12 2 12s3.6 7 10 7a10.4 10.4 0 0 0 5.4-1.6" />
    <path d="M9.9 9.9a3 3 0 0 0 4.2 4.2M3 3l18 18" />
  </svg>
);

/** Only a 401 means the email or password is wrong; any other refusal is not the visitor's typo. */
function isWrongCredentials(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

export function LoginPage() {
  const copy = authCopy.login;
  const token = useAuthToken();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const loginMutation = useLoginMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const [editedSinceSubmit, setEditedSinceSubmit] = useState(false);
  const [submittedOnce, setSubmittedOnce] = useState(false);
  const [focused, setFocused] = useState<'email' | 'password' | null>(null);
  const [passwordShown, setPasswordShown] = useState(false);
  const [shake, setShake] = useState(false);
  const {
    register,
    handleSubmit,
    watch,
    setFocus,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: getRememberedEmail(), password: '', rememberMe: true },
  });
  const returnTo = (location.state as { from?: string } | null)?.from ?? '/';

  const { error: loginError } = loginMutation;
  useEffect(() => {
    if (isWrongCredentials(loginError)) {
      setFocus('password');
    }
  }, [loginError, setFocus]);

  const failed = loginMutation.isError && !editedSinceSubmit;
  const notConfirmed = failed && problemCodeFrom(loginMutation.error) === EMAIL_NOT_CONFIRMED;
  const wrongCredentials = failed && !notConfirmed && isWrongCredentials(loginMutation.error);
  const otherFailure = failed && !notConfirmed && !wrongCredentials;
  const isPending = loginMutation.isPending;
  useAuthPose(nodiPeekState({ focused, pending: isPending, failed, succeeded: submitSucceeded }));

  if (token) {
    return <Navigate to={returnTo} replace />;
  }

  const onSubmit = handleSubmit((values) => {
    if (values.rememberMe) {
      setRememberedEmail(values.email);
    } else {
      clearRememberedEmail();
    }
    setEditedSinceSubmit(false);
    loginMutation.mutate(values, {
      onSuccess: () => {
        setSubmitSucceeded(true);
      },
      onError: (error) => {
        setShake(isWrongCredentials(error));
      },
    });
  });

  // The Google message describes the redirect that opened the page, not any later attempt.
  const oauthError = submittedOnce ? null : searchParams.get('oauth_error');
  const alertMessage = (() => {
    if (notConfirmed) return copy.notConfirmed;
    if (wrongCredentials) return detailFrom(loginMutation) ?? copy.failedFallback;
    if (otherFailure) return detailFrom(loginMutation) ?? copy.retryLater;
    return oauthError ? oauthErrorMessage(oauthError) : undefined;
  })();
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return copy.submitSuccess;
    if (isPending) return copy.submitPending;
    return copy.submit;
  })();

  const markEdited = () => {
    setEditedSinceSubmit(true);
    setShake(false);
  };
  const emailField = register('email', { onChange: markEdited });
  const passwordField = register('password', { onChange: markEdited });
  const passwordDescription = errors.password
    ? 'login-password-error'
    : wrongCredentials
      ? 'login-alert'
      : undefined;

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {copy.title} <span className="auth-title__accent">{copy.titleAccent}</span>
      </h1>
      <p className="auth-subtitle">{copy.subtitle}</p>

      {alertMessage && (
        <div id="login-alert" role="alert" className="auth-alert">
          <p>{alertMessage}</p>
          {wrongCredentials && (
            <p>
              {copy.noAccount} <AuthLink to="/register">{copy.createAccount}</AuthLink>
            </p>
          )}
          {wrongCredentials && oauthEnabled() && <p>{copy.googleHint}</p>}
        </div>
      )}
      {notConfirmed && loginMutation.variables && (
        <ResendConfirmation email={loginMutation.variables.email} autoFocus />
      )}

      <form
        onSubmit={(event) => {
          setSubmittedOnce(true);
          return onSubmit(event);
        }}
        noValidate
        aria-label={copy.formLabel}
      >
        <div className="auth-field">
          <label htmlFor="login-email" className="auth-label">
            {copy.emailLabel}
          </label>
          <div className="auth-control">
            <input
              id="login-email"
              type="email"
              className="auth-input"
              autoComplete="username"
              placeholder={copy.emailPlaceholder}
              disabled={isPending}
              aria-invalid={errors.email ? 'true' : 'false'}
              aria-describedby={errors.email ? 'login-email-error' : undefined}
              {...emailField}
              onFocus={() => setFocused('email')}
              onBlur={(event) => {
                setFocused(null);
                return emailField.onBlur(event);
              }}
            />
          </div>
          {errors.email && (
            <p id="login-email-error" role="alert" className="auth-error">
              {errors.email.message}
            </p>
          )}
        </div>
        <div className="auth-field">
          <label htmlFor="login-password" className="auth-label">
            {copy.passwordLabel}
          </label>
          <div className="auth-control">
            <input
              id="login-password"
              type={passwordShown ? 'text' : 'password'}
              className={shake ? 'auth-input auth-input--shake' : 'auth-input'}
              autoComplete="current-password"
              placeholder={copy.passwordPlaceholder}
              disabled={isPending}
              aria-invalid={errors.password || wrongCredentials ? 'true' : 'false'}
              aria-describedby={passwordDescription}
              onAnimationEnd={() => setShake(false)}
              {...passwordField}
              onFocus={() => setFocused('password')}
              onBlur={(event) => {
                setFocused(null);
                return passwordField.onBlur(event);
              }}
            />
            <button
              type="button"
              className="auth-reveal"
              aria-label={copy.reveal}
              aria-pressed={passwordShown}
              aria-controls="login-password"
              onClick={() => setPasswordShown((shown) => !shown)}
            >
              {passwordShown ? eyeOffIcon : eyeIcon}
            </button>
          </div>
          {errors.password && (
            <p id="login-password-error" role="alert" className="auth-error">
              {errors.password.message}
            </p>
          )}
        </div>
        <div className="auth-row">
          <label className="auth-remember" htmlFor="login-remember">
            <input id="login-remember" type="checkbox" {...register('rememberMe')} />
            <span>{copy.remember}</span>
          </label>
          {passwordResetEnabled() && (
            <Link to="/forgot-password" className="auth-link">
              {copy.forgot}
            </Link>
          )}
        </div>
        <button
          className="auth-btn auth-btn--primary"
          type="submit"
          data-state={dataState}
          disabled={isPending}
          aria-busy={isPending}
        >
          {isPending && <span className="auth-spinner" aria-hidden="true" />}
          {buttonLabel}
        </button>
      </form>

      <hr className="auth-divider" />
      {oauthEnabled() && <p className="auth-hint">{copy.googleHint}</p>}
      <OAuthButton
        provider="google"
        label={copy.google}
        href={googleAuthorizeUrl(watch('rememberMe'), returnTo)}
        className="auth-btn auth-btn--google"
      />

      <p className="auth-footnote">
        {copy.noAccount} <AuthLink to="/register">{copy.createAccount}</AuthLink>
      </p>
    </>
  );
}
