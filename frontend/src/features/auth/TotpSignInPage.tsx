import { useEffect, useRef, useState, type ChangeEvent, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { useLocation, useNavigate, useSearchParams } from 'react-router-dom';
import { activeLocale } from '../../lib/i18n';
import { AuthLink } from './AuthLink';
import { useAuthPose } from './authPose';
import { nodiPeekState } from './nodiPeekState';
import {
  lockEndText,
  RECOVERY_CODE_SHAPE,
  TOTP_CODE_SHAPE,
  totpFailureFrom,
  type TotpFailure,
} from './totpFailure';
import { submitState } from './useAuth';
import { useCompleteTotpSignInMutation, useLockActive } from './useTotp';

const CODE_MAX_LENGTH = 7;
const RECOVERY_MAX_LENGTH = 13;

/** Router state the pages that lead here hand over: where to continue, or the password still to set. */
export interface TotpSignInState {
  from?: string;
  /** Set by an emailed link that also needs a password: continue to `/set-password` with this token. */
  passwordSetup?: { token: string | null };
}

/** A same-origin path, else the home page: a return target comes from an address bar or router state. */
function safeReturnPath(value: unknown): string {
  if (typeof value !== 'string' || !value.startsWith('/')) return '/';
  try {
    const url = new URL(value, window.location.origin);
    return url.origin === window.location.origin ? url.pathname + url.search + url.hash : '/';
  } catch {
    return '/';
  }
}

/**
 * The second step of a sign-in whose password (or Google, or emailed link) was accepted: a code from the
 * authenticator app, or a recovery code. The challenge it completes travels in an HttpOnly cookie, so a
 * direct visit without one simply meets the expired state on the first try.
 */
export function TotpSignInPage() {
  const { t } = useTranslation();
  const navigate = useNavigate();
  const location = useLocation();
  const [searchParams] = useSearchParams();
  const state = location.state as TotpSignInState | null;
  const complete = useCompleteTotpSignInMutation();
  const inputRef = useRef<HTMLInputElement>(null);
  const [recovery, setRecovery] = useState(false);
  const [code, setCode] = useState('');
  const [shapeError, setShapeError] = useState(false);
  const [failure, setFailure] = useState<TotpFailure | null>(null);
  const [succeeded, setSucceeded] = useState(false);

  const lockedUntil = failure?.kind === 'locked' ? failure.lockedUntil : null;
  const lockActive = useLockActive(lockedUntil);
  // A lock whose end time is unknown cannot be waited out, so it never blocks submit.
  const locked = failure?.kind === 'locked' && (lockedUntil === null || lockActive);
  const expired = failure?.kind === 'expired';
  const pending = complete.isPending;
  const shown = failure && (failure.kind !== 'locked' || locked) ? failure : null;
  useAuthPose(
    nodiPeekState({ focused: null, pending, failed: shown !== null || shapeError, succeeded }),
  );

  // The field cannot take focus while it is disabled for the request, so focus returns once it settles.
  useEffect(() => {
    if (!pending && failure && failure.kind !== 'expired') inputRef.current?.focus();
  }, [pending, failure]);

  const returnTo = safeReturnPath(state?.from ?? searchParams.get('return_to'));

  const reset = (nextRecovery: boolean) => {
    setRecovery(nextRecovery);
    setCode('');
    setShapeError(false);
    setFailure((current) =>
      current?.kind === 'locked' || current?.kind === 'expired' ? current : null,
    );
    inputRef.current?.focus();
  };

  const onChange = (event: ChangeEvent<HTMLInputElement>) => {
    setCode(event.target.value);
    setShapeError(false);
    setFailure((current) =>
      current?.kind === 'locked' || current?.kind === 'expired' ? current : null,
    );
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (pending || lockActive || expired) return;
    const value = code.trim();
    if (!(recovery ? RECOVERY_CODE_SHAPE : TOTP_CODE_SHAPE).test(value)) {
      setShapeError(true);
      inputRef.current?.focus();
      return;
    }
    setShapeError(false);
    setFailure(null);
    complete.mutate(value, {
      onSuccess: () => {
        setSucceeded(true);
        if (state?.passwordSetup) {
          navigate('/set-password', {
            replace: true,
            state: { passwordSetupToken: state.passwordSetup.token },
          });
        } else {
          navigate(returnTo, { replace: true });
        }
      },
      onError: (error) => {
        setCode('');
        setFailure(totpFailureFrom(error));
      },
    });
  };

  const alertText = (() => {
    switch (shown?.kind) {
      case 'invalid':
        return t(recovery ? 'auth.totpSignIn.invalidRecovery' : 'auth.totpSignIn.invalid');
      case 'locked':
        return shown.lockedUntil
          ? t('auth.totpSignIn.locked', { time: lockEndText(shown.lockedUntil, activeLocale()) })
          : t('auth.totpSignIn.lockedLater');
      case 'expired':
        return t('auth.totpSignIn.expired');
      case 'unavailable':
        return t('auth.totpSignIn.unavailable');
      case 'unknown':
        return t('auth.totpSignIn.failed');
      default:
        return undefined;
    }
  })();

  const mode = recovery ? 'recovery' : 'code';
  const errorId = 'totp-sign-in-error';
  const describedBy = shapeError
    ? errorId
    : alertText && !expired
      ? 'totp-sign-in-alert'
      : undefined;
  const buttonLabel = (() => {
    if (succeeded) return t('auth.totpSignIn.succeeded');
    if (pending) return t('auth.totpSignIn.submitting');
    return t('auth.totpSignIn.submit');
  })();

  return (
    <>
      <h1 className="auth-title" tabIndex={-1}>
        {t('auth.totpSignIn.title')}
      </h1>

      {expired ? (
        <>
          <div role="alert" className="auth-alert">
            <p>{alertText}</p>
          </div>
          <p className="auth-footnote">
            <AuthLink to="/login">{t('auth.totpSignIn.backToSignIn')}</AuthLink>
          </p>
        </>
      ) : (
        <>
          <p className="auth-subtitle">{t(`auth.totpSignIn.${mode}Intro`)}</p>

          {alertText && (
            <div id="totp-sign-in-alert" role="alert" className="auth-alert">
              <p>{alertText}</p>
            </div>
          )}

          <form
            className="auth-form"
            onSubmit={onSubmit}
            noValidate
            aria-label={t('auth.totpSignIn.formLabel')}
          >
            <div className="auth-field">
              <label htmlFor="totp-sign-in-code" className="auth-label">
                {t(`auth.totpSignIn.${mode}Label`)}
              </label>
              <div className="auth-control">
                <input
                  ref={inputRef}
                  id="totp-sign-in-code"
                  type="text"
                  className="auth-input"
                  inputMode={recovery ? 'text' : 'numeric'}
                  autoComplete={recovery ? 'off' : 'one-time-code'}
                  autoCapitalize={recovery ? 'characters' : 'off'}
                  autoCorrect="off"
                  spellCheck={false}
                  maxLength={recovery ? RECOVERY_MAX_LENGTH : CODE_MAX_LENGTH}
                  placeholder={t(`auth.totpSignIn.${mode}Placeholder`)}
                  value={code}
                  onChange={onChange}
                  disabled={pending}
                  aria-invalid={shapeError || shown?.kind === 'invalid' ? 'true' : 'false'}
                  aria-describedby={describedBy}
                />
              </div>
              {shapeError && (
                <p id={errorId} role="alert" className="auth-error">
                  {t(`auth.totpSignIn.${mode}Shape`)}
                </p>
              )}
            </div>
            <button
              className="auth-btn auth-btn--primary"
              type="submit"
              data-state={submitState(succeeded, pending)}
              disabled={pending || lockActive}
              aria-busy={pending}
            >
              {pending && <span className="auth-spinner" aria-hidden="true" />}
              {buttonLabel}
            </button>
            <button
              type="button"
              className="auth-btn auth-btn--secondary"
              disabled={pending}
              onClick={() => reset(!recovery)}
            >
              {t(recovery ? 'auth.totpSignIn.useCode' : 'auth.totpSignIn.useRecovery')}
            </button>
          </form>

          <p className="auth-footnote">
            <AuthLink to="/login">{t('auth.totpSignIn.backToSignIn')}</AuthLink>
          </p>
        </>
      )}
    </>
  );
}
