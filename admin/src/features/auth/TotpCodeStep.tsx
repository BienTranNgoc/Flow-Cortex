import { zodResolver } from '@hookform/resolvers/zod';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { ArrowRight, CircleNotch, WarningCircle } from '@phosphor-icons/react';
import { activeLocale } from '../../lib/i18n';
import { totpFailure, totpFailureMessage } from './totpFailure';
import { signInCodeSchema, type CodeFormValues } from './totpSchemas';
import { useCompleteTotpSignInMutation } from './useTotp';
import { useIsLocked } from './useIsLocked';

/**
 * The second step of a sign-in the server answered with a challenge: an authenticator code or a
 * recovery code. A wrong code can be retried until the challenge dies or the account locks.
 */
export function TotpCodeStep({ onBack }: Readonly<{ onBack: () => void }>) {
  const { t } = useTranslation();
  const complete = useCompleteTotpSignInMutation();
  const {
    register,
    handleSubmit,
    resetField,
    setFocus,
    formState: { errors },
  } = useForm<CodeFormValues>({ resolver: zodResolver(signInCodeSchema) });

  const failure = complete.error ? totpFailure(complete.error) : undefined;
  const locked = useIsLocked(failure?.kind === 'locked' ? failure.lockedUntil : undefined);
  const expired = failure?.kind === 'expired';
  const message = failure ? totpFailureMessage(failure, t, activeLocale()) : undefined;

  const onSubmit = handleSubmit((values) =>
    complete.mutate(values.code, {
      onError: () => {
        resetField('code');
        setFocus('code');
      },
    }),
  );

  return (
    <div className="auth-shell__form-inner">
      <header className="auth-shell__form-header">
        <span className="auth-shell__eyebrow">{t('auth.totp.signIn.eyebrow')}</span>
        <h1>{t('auth.totp.signIn.title')}</h1>
        <p>{t('auth.totp.signIn.subtitle')}</p>
      </header>

      {expired ? (
        <div className="auth-form">
          <p className="auth-form__error auth-form__error--server" role="alert">
            <WarningCircle aria-hidden="true" size={14} />
            {message}
          </p>
          <button type="button" className="auth-form__submit" onClick={onBack}>
            {t('auth.totp.signIn.back')}
          </button>
        </div>
      ) : (
        <form
          onSubmit={onSubmit}
          noValidate
          aria-label={t('auth.totp.signIn.title')}
          className="auth-form"
        >
          <div className="auth-form__field">
            <label htmlFor="totp-code" className="auth-form__label">
              {t('auth.totp.signIn.code')}
            </label>
            <input
              id="totp-code"
              type="text"
              autoComplete="one-time-code"
              autoCapitalize="characters"
              spellCheck={false}
              autoFocus
              className={`auth-form__input${errors.code ? ' is-invalid' : ''}`}
              aria-invalid={errors.code ? true : undefined}
              aria-describedby={errors.code ? 'totp-code-error' : undefined}
              {...register('code')}
            />
            {errors.code?.message && (
              <p id="totp-code-error" className="auth-form__error" role="alert">
                <WarningCircle aria-hidden="true" size={14} />
                {t(errors.code.message)}
              </p>
            )}
          </div>

          {message && (
            <p className="auth-form__error auth-form__error--server" role="alert">
              <WarningCircle aria-hidden="true" size={14} />
              {message}
            </p>
          )}

          <button
            type="submit"
            className="auth-form__submit"
            disabled={complete.isPending || locked}
          >
            {complete.isPending ? (
              <>
                <CircleNotch aria-hidden="true" size={16} className="auth-form__spinner" />
                {t('auth.totp.signIn.submitting')}
              </>
            ) : (
              <>
                {t('auth.totp.signIn.submit')}
                <ArrowRight aria-hidden="true" size={16} />
              </>
            )}
          </button>
          <button type="button" className="cortex-admin-btn" onClick={onBack}>
            {t('auth.totp.signIn.back')}
          </button>
        </form>
      )}
    </div>
  );
}
