import { ApiError } from '@flow-cortex/api-client';
import { zodResolver } from '@hookform/resolvers/zod';
import { useEffect, useState } from 'react';
import { useForm } from 'react-hook-form';
import { Trans, useTranslation } from 'react-i18next';
import { Link, Navigate, useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { oauthEnabled } from '../../app/featureFlags';
import { OAuthButton } from '../../app/OAuthButton';
import { useFeatures } from '../../app/useFeatures';
import { activeLocale } from '../../lib/i18n';
import { AuthLink } from './AuthLink';
import { useAuthPose } from './authPose';
import { ResendConfirmation } from './ConfirmEmailPage';
import { loginSchema, type LoginFormValues } from './loginSchema';
import { nodiPeekState } from './nodiPeekState';
import { googleAuthorizeUrl, oauthErrorKey } from './oauth';
import {
  accountLockedMessage,
  clearRememberedEmail,
  EMAIL_NOT_CONFIRMED,
  failureMessage,
  getRememberedEmail,
  lockedUntilFrom,
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

/** Router state other pages hand to sign-in: where to return, or a notice and the email to fill in. */
interface LoginState {
  from?: string;
  notice?: string;
  email?: string;
}

function localTime(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0');
  return `${pad(date.getHours())}:${pad(date.getMinutes())}`;
}

/** Only a 401 means the email or password is wrong; any other refusal is not the visitor's typo. */
function isWrongCredentials(error: unknown): boolean {
  return error instanceof ApiError && error.status === 401;
}

export function LoginPage() {
  const { t } = useTranslation();
  const token = useAuthToken();
  const location = useLocation();
  const navigate = useNavigate();
  const [searchParams] = useSearchParams();
  const { passwordReset, supportWidget } = useFeatures();
  const loginMutation = useLoginMutation();
  const [submitSucceeded, setSubmitSucceeded] = useState(false);
  const [editedSinceSubmit, setEditedSinceSubmit] = useState(false);
  const [submittedOnce, setSubmittedOnce] = useState(false);
  const [focused, setFocused] = useState<'email' | 'password' | null>(null);
  const [passwordShown, setPasswordShown] = useState(false);
  const [shake, setShake] = useState(false);
  const state = location.state as LoginState | null;
  const {
    register,
    handleSubmit,
    watch,
    setFocus,
    formState: { errors },
  } = useForm<LoginFormValues>({
    resolver: zodResolver(loginSchema),
    defaultValues: { email: state?.email ?? getRememberedEmail(), password: '', rememberMe: true },
  });
  const returnTo = state?.from ?? '/';
  const [notice, setNotice] = useState(state?.notice);

  // The browser restores history state on reload, so the notice leaves it once shown; the email stays.
  useEffect(() => {
    if (state?.notice === undefined) return;
    const { notice: _shown, ...kept } = state;
    navigate(
      { pathname: location.pathname, search: location.search },
      { replace: true, state: kept },
    );
  }, [state, location.pathname, location.search, navigate]);

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
        setNotice(undefined);
        setShake(isWrongCredentials(error));
      },
    });
  });

  // The Google message describes the redirect that opened the page, not any later attempt.
  const oauthError = submittedOnce ? null : searchParams.get('oauth_error');
  const lockedUntil = otherFailure ? lockedUntilFrom(loginMutation.error) : undefined;
  const lockedText = otherFailure
    ? accountLockedMessage(loginMutation.error, t, activeLocale())
    : undefined;
  const resetLink = <Link to="/forgot-password" />;
  // A link to a panel that never opens would be a dead end, so the sentence stays plain text.
  const helpParams = new URLSearchParams(searchParams);
  helpParams.set('help', 'home');
  const helpLink = supportWidget ? <Link to={{ search: `?${helpParams}` }} /> : <></>;
  const alertBody = (() => {
    if (notConfirmed) return t('auth.login.emailNotConfirmed');
    if (wrongCredentials) return failureMessage(loginMutation, t, 'auth.login.failedFallback');
    if (otherFailure) {
      if (lockedUntil) {
        if (lockedText) {
          return (
            <>
              {lockedText}
              {passwordReset && (
                <>
                  {' '}
                  <Trans
                    i18nKey="auth.login.lockedResetOffer"
                    components={{ reset: resetLink }}
                  />
                </>
              )}
            </>
          );
        }
        return passwordReset ? (
          <Trans
            i18nKey="auth.login.lockedWithReset"
            values={{ time: localTime(lockedUntil) }}
            components={{ reset: resetLink }}
          />
        ) : (
          t('auth.login.locked', { time: localTime(lockedUntil) })
        );
      }
      return lockedText ?? failureMessage(loginMutation, t, 'auth.login.retryLater');
    }
    return oauthError ? (
      <Trans i18nKey={oauthErrorKey(oauthError)} components={{ help: helpLink }} />
    ) : undefined;
  })();
  const dataState = submitState(submitSucceeded, isPending);

  const buttonLabel = (() => {
    if (submitSucceeded) return t('auth.login.succeeded');
    if (isPending) return t('auth.login.submitting');
    return t('auth.login.submit');
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
        {t('auth.login.title')}{' '}
        <span className="auth-title__accent">{t('auth.login.titleAccent')}</span>
      </h1>
      <p className="auth-subtitle">{t('auth.login.subtitle')}</p>

      {notice && (
        <p role="status" className="auth-status">
          {notice}
        </p>
      )}

      {alertBody && (
        <div id="login-alert" role="alert" className="auth-alert">
          <p>{alertBody}</p>
          {wrongCredentials && (
            <p>
              {t('auth.login.newHere')}{' '}
              <AuthLink to="/register">{t('auth.login.createAccount')}</AuthLink>
            </p>
          )}
          {wrongCredentials && oauthEnabled() && <p>{t('auth.login.googleHint')}</p>}
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
        aria-label={t('auth.login.formLabel')}
      >
        <div className="auth-field">
          <label htmlFor="login-email" className="auth-label">
            {t('auth.login.emailLabel')}
          </label>
          <div className="auth-control">
            <input
              id="login-email"
              type="email"
              className="auth-input"
              autoComplete="username"
              placeholder={t('auth.login.emailPlaceholder')}
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
          {errors.email?.message && (
            <p id="login-email-error" role="alert" className="auth-error">
              {t(errors.email.message)}
            </p>
          )}
        </div>
        <div className="auth-field">
          <label htmlFor="login-password" className="auth-label">
            {t('auth.login.passwordLabel')}
          </label>
          <div className="auth-control">
            <input
              id="login-password"
              type={passwordShown ? 'text' : 'password'}
              className={shake ? 'auth-input auth-input--shake' : 'auth-input'}
              autoComplete="current-password"
              placeholder={t('auth.login.passwordPlaceholder')}
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
              aria-label={t('auth.login.showPassword')}
              aria-pressed={passwordShown}
              aria-controls="login-password"
              onClick={() => setPasswordShown((shown) => !shown)}
            >
              {passwordShown ? eyeOffIcon : eyeIcon}
            </button>
          </div>
          {errors.password?.message && (
            <p id="login-password-error" role="alert" className="auth-error">
              {t(errors.password.message)}
            </p>
          )}
        </div>
        <div className="auth-row">
          <label className="auth-remember" htmlFor="login-remember">
            <input id="login-remember" type="checkbox" {...register('rememberMe')} />
            <span>{t('auth.login.rememberMe')}</span>
          </label>
          {passwordReset ? (
            <Link to="/forgot-password" className="auth-link forgot-password-link">
              {t('auth.login.forgotPassword')}
            </Link>
          ) : (
            <span
              className="auth-link forgot-password-link forgot-password-link--disabled"
              title={t('auth.login.forgotPasswordDisabled')}
            >
              {t('auth.login.forgotPassword')}
            </span>
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
      {oauthEnabled() && <p className="auth-hint">{t('auth.login.googleHint')}</p>}
      <OAuthButton
        provider="google"
        label={t('auth.login.google')}
        href={googleAuthorizeUrl(watch('rememberMe'), returnTo)}
        className="auth-btn auth-btn--google"
      />

      <p className="auth-footnote">
        {t('auth.login.newHere')}{' '}
        <AuthLink to="/register">{t('auth.login.createAccount')}</AuthLink>
      </p>
      <p className="auth-footnote">
        {t('auth.login.noPassword')}{' '}
        <AuthLink to="/set-password">{t('auth.login.emailMeLink')}</AuthLink>
      </p>
    </>
  );
}
