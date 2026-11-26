import { zodResolver } from '@hookform/resolvers/zod';
import { useQueryClient } from '@tanstack/react-query';
import { useEffect, useRef, useState } from 'react';
import { useForm } from 'react-hook-form';
import { useTranslation } from 'react-i18next';
import { setToken } from '@flow-cortex/api-client';
import { ArrowRight, CircleNotch, WarningCircle } from '@phosphor-icons/react';
import { usePageTitle } from '../../app/usePageTitle';
import { AppLanguageToggle } from '../../lib/AppLanguageToggle';
import { activeLocale } from '../../lib/i18n';
import { AuthBrand } from './AuthBrand';
import { totpFailure, totpFailureMessage } from './totpFailure';
import { enrollCodeSchema, type CodeFormValues } from './totpSchemas';
import { logout } from './useAuth';
import { useIsLocked } from './useIsLocked';
import { useEnableTotpMutation, useStartTotpSetupMutation, type TotpEnabled } from './useTotp';
import './twoFactorSetup.css';

const CODES_FILE_NAME = 'flow-cortex-recovery-codes.txt';

/** The secret in groups of four, the way an authenticator app shows it for typing by hand. */
function groupedKey(secret: string): string {
  return secret.match(/.{1,4}/g)?.join(' ') ?? secret;
}

function downloadCodes(codes: readonly string[]) {
  const objectUrl = URL.createObjectURL(
    new Blob([`${codes.join('\n')}\n`], { type: 'text/plain' }),
  );
  try {
    const anchor = document.createElement('a');
    anchor.href = objectUrl;
    anchor.download = CODES_FILE_NAME;
    document.body.appendChild(anchor);
    anchor.click();
    anchor.remove();
  } finally {
    URL.revokeObjectURL(objectUrl);
  }
}

/**
 * The only screen a console account that must have a second factor and has none can reach: scan,
 * confirm with a code, read the recovery codes once, then continue into the console on the session
 * that passed TOTP. The new token is kept back until "continue" so the codes stay on screen.
 */
export function TwoFactorSetupPage({ email }: Readonly<{ email: string | undefined }>) {
  const { t } = useTranslation();
  usePageTitle(t('auth.setup.title'));
  const queryClient = useQueryClient();
  const setup = useStartTotpSetupMutation();
  const enable = useEnableTotpMutation();
  const { mutate: startSetup } = setup;
  const started = useRef(false);

  useEffect(() => {
    if (started.current) return;
    started.current = true;
    startSetup();
  }, [startSetup]);

  const enableFailure = enable.error ? totpFailure(enable.error) : undefined;
  useEffect(() => {
    if (enableFailure?.kind === 'alreadyEnabled') {
      void queryClient.invalidateQueries({ queryKey: ['me'] });
    }
  }, [enableFailure?.kind, queryClient]);

  const restart = () => {
    enable.reset();
    startSetup();
  };
  const finish = (enabled: TotpEnabled) => {
    setToken(enabled.accessToken, true);
    void queryClient.invalidateQueries({ queryKey: ['me'] });
  };

  return (
    <main className="auth-shell">
      <div className="auth-shell__language">
        <AppLanguageToggle />
      </div>
      <section className="auth-shell__card">
        <AuthBrand />
        <div className="auth-shell__form-inner">
          {enable.data ? (
            <RecoveryCodes enabled={enable.data} onContinue={() => finish(enable.data)} />
          ) : (
            <>
              <header className="auth-shell__form-header">
                <span className="auth-shell__eyebrow">{t('auth.setup.eyebrow')}</span>
                <h1>{t('auth.setup.title')}</h1>
                <p>{t('auth.setup.subtitle')}</p>
              </header>
              {setup.isPending || setup.isIdle ? (
                <output className="two-factor-setup__note">{t('auth.setup.preparing')}</output>
              ) : setup.isError ? (
                <div className="auth-form">
                  <p className="auth-form__error auth-form__error--server" role="alert">
                    <WarningCircle aria-hidden="true" size={14} />
                    {totpFailureMessage(totpFailure(setup.error), t, activeLocale())}
                  </p>
                  <button type="button" className="cortex-admin-btn" onClick={() => startSetup()}>
                    {t('auth.setup.retry')}
                  </button>
                </div>
              ) : (
                <ConfirmForm
                  qrDataUrl={setup.data.qrDataUrl}
                  secret={setup.data.secret}
                  pending={enable.isPending}
                  failure={enableFailure}
                  onConfirm={(code) => enable.mutate(code)}
                  onRestart={restart}
                />
              )}
            </>
          )}
        </div>
        <footer className="auth-shell__form-foot">
          {email && <span>{t('auth.setup.signedInAs', { email })}</span>}
          <button type="button" className="cortex-admin-btn" onClick={() => void logout()}>
            {t('auth.setup.signOut')}
          </button>
        </footer>
      </section>
    </main>
  );
}

interface ConfirmFormProps {
  readonly qrDataUrl: string;
  readonly secret: string;
  readonly pending: boolean;
  readonly failure: ReturnType<typeof totpFailure> | undefined;
  readonly onConfirm: (code: string) => void;
  readonly onRestart: () => void;
}

function ConfirmForm({
  qrDataUrl,
  secret,
  pending,
  failure,
  onConfirm,
  onRestart,
}: Readonly<ConfirmFormProps>) {
  const { t } = useTranslation();
  const {
    register,
    handleSubmit,
    resetField,
    setFocus,
    formState: { errors },
  } = useForm<CodeFormValues>({ resolver: zodResolver(enrollCodeSchema) });
  const locked = useIsLocked(failure?.kind === 'locked' ? failure.lockedUntil : undefined);
  const message = failure ? totpFailureMessage(failure, t, activeLocale()) : undefined;

  const failureKind = failure?.kind;
  useEffect(() => {
    if (failureKind === 'invalid') {
      resetField('code');
      setFocus('code');
    }
  }, [failureKind, resetField, setFocus]);

  return (
    <form
      onSubmit={handleSubmit((values) => onConfirm(values.code))}
      noValidate
      aria-label={t('auth.setup.title')}
      className="auth-form"
    >
      <p className="two-factor-setup__note">{t('auth.setup.scan')}</p>
      <img className="two-factor-setup__qr" src={qrDataUrl} alt={t('auth.setup.qrAlt')} />
      <p className="two-factor-setup__key">
        <span>{t('auth.setup.manualKey')}</span>
        <code>{groupedKey(secret)}</code>
      </p>

      <div className="auth-form__field">
        <label htmlFor="setup-code" className="auth-form__label">
          {t('auth.setup.code')}
        </label>
        <input
          id="setup-code"
          type="text"
          inputMode="numeric"
          autoComplete="one-time-code"
          className={`auth-form__input${errors.code ? ' is-invalid' : ''}`}
          aria-invalid={errors.code ? true : undefined}
          aria-describedby={errors.code ? 'setup-code-error' : undefined}
          {...register('code')}
        />
        {errors.code?.message && (
          <p id="setup-code-error" className="auth-form__error" role="alert">
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

      <button type="submit" className="auth-form__submit" disabled={pending || locked}>
        {pending ? (
          <>
            <CircleNotch aria-hidden="true" size={16} className="auth-form__spinner" />
            {t('auth.setup.submitting')}
          </>
        ) : (
          <>
            {t('auth.setup.submit')}
            <ArrowRight aria-hidden="true" size={16} />
          </>
        )}
      </button>
      {failure?.kind === 'restart' && (
        <button type="button" className="cortex-admin-btn" onClick={onRestart}>
          {t('auth.setup.restart')}
        </button>
      )}
    </form>
  );
}

function RecoveryCodes({
  enabled,
  onContinue,
}: Readonly<{ enabled: TotpEnabled; onContinue: () => void }>) {
  const { t } = useTranslation();
  const [copy, setCopy] = useState<'idle' | 'copied' | 'failed'>('idle');
  const codes = enabled.recoveryCodes;

  const copyCodes = async () => {
    try {
      await navigator.clipboard.writeText(codes.join('\n'));
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  return (
    <>
      <header className="auth-shell__form-header">
        <span className="auth-shell__eyebrow">{t('auth.setup.eyebrow')}</span>
        <h1>{t('auth.setup.codesTitle')}</h1>
        <p>{t('auth.setup.codesHint')}</p>
      </header>
      <ol className="two-factor-setup__codes" aria-label={t('auth.setup.codesTitle')}>
        {codes.map((code) => (
          <li key={code}>
            <code>{code}</code>
          </li>
        ))}
      </ol>
      <div className="two-factor-setup__actions">
        <button type="button" className="cortex-admin-btn" onClick={() => void copyCodes()}>
          {t('auth.setup.copy')}
        </button>
        <button type="button" className="cortex-admin-btn" onClick={() => downloadCodes(codes)}>
          {t('auth.setup.download')}
        </button>
      </div>
      <output className="two-factor-setup__note">
        {copy === 'copied' && t('auth.setup.copied')}
        {copy === 'failed' && t('auth.setup.copyFailed')}
      </output>
      <button type="button" className="auth-form__submit" onClick={onContinue}>
        {t('auth.setup.continue')}
        <ArrowRight aria-hidden="true" size={16} />
      </button>
    </>
  );
}
