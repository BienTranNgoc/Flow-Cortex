import { useEffect, useId, useRef, useState, type FormEvent, type RefObject } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError } from '@flow-cortex/api-client';
import type { TotpSetupResponse } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { activeLocale } from '../../lib/i18n';
import { lockEndText, RECOVERY_CODE_SHAPE, TOTP_CODE_SHAPE, totpFailureFrom } from './totpFailure';
import {
  useDisableTotpMutation,
  useEnableTotpMutation,
  useLockActive,
  useRegenerateRecoveryCodesMutation,
  useStartTotpSetupMutation,
  useTotpStatusQuery,
} from './useTotp';
import './TwoFactorSection.css';

const COPIED_RESET_MS = 4000;
const RECOVERY_FILE_NAME = 'nodi-recovery-codes.txt';

type CopyState = 'idle' | 'copied' | 'failed';

type View =
  | { name: 'idle' }
  | { name: 'scan'; setup: TotpSetupResponse }
  | { name: 'codes'; codes: string[]; origin: 'enabled' | 'regenerated' };

type DialogName = 'regenerate' | 'disable';

/** What a refused request says to the member, from its problem code first and its status after. */
function refusalText(t: TFunction, error: unknown): string {
  const code = error instanceof ApiError ? error.problem?.code : undefined;
  if (code === 'TOTP_REQUIRED_FOR_ROLE') return t('auth.twoFactor.errors.requiredForRole');
  if (code === 'TOTP_NOT_SET_UP') return t('auth.twoFactor.errors.notSetUp');
  const failure = totpFailureFrom(error);
  switch (failure.kind) {
    case 'invalid':
      return t('auth.twoFactor.errors.invalid');
    case 'locked':
      return failure.lockedUntil
        ? t('auth.twoFactor.errors.locked', {
            time: lockEndText(failure.lockedUntil, activeLocale()),
          })
        : t('auth.twoFactor.errors.lockedLater');
    case 'unavailable':
      return t('auth.twoFactor.errors.unavailable');
    default:
      return t('auth.twoFactor.errors.failed');
  }
}

/** The end of the lock a refusal reports, or null when it is not a lock or carries no end time. */
function lockOf(error: unknown): Date | null {
  const failure = totpFailureFrom(error);
  return failure.kind === 'locked' ? failure.lockedUntil : null;
}

function useCopy(): [CopyState, (text: string) => Promise<void>] {
  const [state, setState] = useState<CopyState>('idle');
  useEffect(() => {
    if (state !== 'copied') return undefined;
    const id = setTimeout(() => setState('idle'), COPIED_RESET_MS);
    return () => clearTimeout(id);
  }, [state]);
  const copy = async (text: string) => {
    try {
      await navigator.clipboard.writeText(text);
      setState('copied');
    } catch {
      setState('failed');
    }
  };
  return [state, copy];
}

function downloadText(fileName: string, text: string) {
  const url = URL.createObjectURL(new Blob([text], { type: 'text/plain;charset=utf-8' }));
  const link = document.createElement('a');
  link.href = url;
  link.download = fileName;
  document.body.append(link);
  link.click();
  link.remove();
  setTimeout(() => URL.revokeObjectURL(url), 0);
}

interface CodeFieldProps {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  /** `totp`: an authenticator code only; `any`: a recovery code is accepted too. */
  accepts: 'totp' | 'any';
  disabled: boolean;
  invalid: boolean;
  describedBy: string | undefined;
  inputRef: RefObject<HTMLInputElement>;
}

function CodeField({
  id,
  label,
  value,
  onChange,
  accepts,
  disabled,
  invalid,
  describedBy,
  inputRef,
}: Readonly<CodeFieldProps>) {
  const totpOnly = accepts === 'totp';
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      <input
        ref={inputRef}
        id={id}
        type="text"
        inputMode={totpOnly ? 'numeric' : 'text'}
        autoComplete={totpOnly ? 'one-time-code' : 'off'}
        autoCapitalize={totpOnly ? 'off' : 'characters'}
        autoCorrect="off"
        spellCheck={false}
        maxLength={totpOnly ? 7 : 13}
        value={value}
        disabled={disabled}
        onChange={(event) => onChange(event.target.value)}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
      />
    </div>
  );
}

function matchesShape(value: string, accepts: 'totp' | 'any'): boolean {
  const trimmed = value.trim();
  return accepts === 'totp'
    ? TOTP_CODE_SHAPE.test(trimmed)
    : TOTP_CODE_SHAPE.test(trimmed) || RECOVERY_CODE_SHAPE.test(trimmed);
}

interface SetupScanProps {
  setup: TotpSetupResponse;
  pending: boolean;
  error: unknown;
  onConfirm: (code: string) => void;
  onCancel: () => void;
}

/** The QR code and manual key of a pending secret, and the field that confirms the first code. */
function SetupScan({ setup, pending, error, onConfirm, onCancel }: Readonly<SetupScanProps>) {
  const { t } = useTranslation();
  const ids = useId();
  const codeRef = useRef<HTMLInputElement>(null);
  const [code, setCode] = useState('');
  const [shapeError, setShapeError] = useState(false);
  const [copyState, copy] = useCopy();
  const lockActive = useLockActive(lockOf(error));

  useEffect(() => {
    codeRef.current?.focus();
  }, []);
  useEffect(() => {
    if (error) {
      setCode('');
      codeRef.current?.focus();
    }
  }, [error]);

  const lockOnly = Boolean(error) && totpFailureFrom(error).kind === 'locked' && !lockActive;
  const refusal = error && !lockOnly ? refusalText(t, error) : null;
  const errorId = `${ids}-error`;

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    if (pending || lockActive) return;
    if (!matchesShape(code, 'totp')) {
      setShapeError(true);
      codeRef.current?.focus();
      return;
    }
    setShapeError(false);
    onConfirm(code.trim());
  };

  return (
    <div className="two-factor-scan" role="group" aria-labelledby={`${ids}-title`}>
      <h3 id={`${ids}-title`}>{t('auth.twoFactor.scan.title')}</h3>
      <p>{t('auth.twoFactor.scan.hint')}</p>
      <img
        className="two-factor-qr"
        src={setup.qrDataUrl}
        alt={t('auth.twoFactor.scan.qrAlt')}
        width={224}
        height={224}
      />
      <div className="field">
        <label htmlFor={`${ids}-key`}>{t('auth.twoFactor.scan.manualLabel')}</label>
        <input
          id={`${ids}-key`}
          readOnly
          value={setup.secret.replace(/(.{4})(?=.)/g, '$1 ')}
          onFocus={(event) => event.target.select()}
          autoComplete="off"
          spellCheck={false}
        />
      </div>
      <div className="two-factor-actions">
        <button
          type="button"
          className="cortex-btn cortex-btn--ghost"
          onClick={() => void copy(setup.secret)}
        >
          {t('auth.twoFactor.scan.copyKey')}
        </button>
      </div>
      <p className="two-factor-copied" role="status" aria-live="polite">
        {copyState === 'copied' && t('auth.twoFactor.scan.keyCopied')}
        {copyState === 'failed' && t('auth.twoFactor.scan.copyFailed')}
      </p>
      <form onSubmit={onSubmit} noValidate>
        <CodeField
          id={`${ids}-code`}
          label={t('auth.twoFactor.scan.codeLabel')}
          value={code}
          onChange={(value) => {
            setCode(value);
            setShapeError(false);
          }}
          accepts="totp"
          disabled={pending}
          invalid={shapeError || Boolean(refusal)}
          describedBy={shapeError || refusal ? errorId : undefined}
          inputRef={codeRef}
        />
        {(shapeError || refusal) && (
          <p id={errorId} role="alert" className="form-error">
            {shapeError ? t('auth.twoFactor.errors.totpShape') : refusal}
          </p>
        )}
        <div className="two-factor-actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={onCancel}
            disabled={pending}
          >
            {t('auth.twoFactor.scan.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={pending || lockActive}
            aria-busy={pending}
          >
            {pending ? t('auth.twoFactor.scan.confirming') : t('auth.twoFactor.scan.confirm')}
          </button>
        </div>
      </form>
    </div>
  );
}

interface RecoveryCodesProps {
  codes: string[];
  origin: 'enabled' | 'regenerated';
  onDone: () => void;
}

/** The recovery codes, shown once, with copy and download and an acknowledgement. */
function RecoveryCodes({ codes, origin, onDone }: Readonly<RecoveryCodesProps>) {
  const { t } = useTranslation();
  const titleId = useId();
  const titleRef = useRef<HTMLHeadingElement>(null);
  const [copyState, copy] = useCopy();

  useEffect(() => {
    titleRef.current?.focus();
  }, []);

  const fileText = `${t('auth.twoFactor.codes.fileHeader')}\n\n${codes.join('\n')}\n`;

  return (
    <div className="two-factor-codes" role="group" aria-labelledby={titleId}>
      <h3 id={titleId} ref={titleRef} tabIndex={-1}>
        {t(
          origin === 'enabled'
            ? 'auth.twoFactor.codes.titleEnabled'
            : 'auth.twoFactor.codes.titleRegenerated',
        )}
      </h3>
      <p className="two-factor-codes__warning">{t('auth.twoFactor.codes.warning')}</p>
      <ul className="two-factor-codes__list" aria-label={t('auth.twoFactor.codes.listLabel')}>
        {codes.map((recoveryCode) => (
          <li key={recoveryCode}>
            <code>{recoveryCode}</code>
          </li>
        ))}
      </ul>
      <div className="two-factor-actions">
        <button
          type="button"
          className="cortex-btn cortex-btn--ghost"
          onClick={() => void copy(codes.join('\n'))}
        >
          {t('auth.twoFactor.codes.copy')}
        </button>
        <button
          type="button"
          className="cortex-btn cortex-btn--ghost"
          onClick={() => downloadText(RECOVERY_FILE_NAME, fileText)}
        >
          {t('auth.twoFactor.codes.download')}
        </button>
        <button type="button" className="cortex-btn cortex-btn--primary" onClick={onDone}>
          {t('auth.twoFactor.codes.saved')}
        </button>
      </div>
      <p className="two-factor-copied" role="status" aria-live="polite">
        {copyState === 'copied' && t('auth.twoFactor.codes.copied')}
        {copyState === 'failed' && t('auth.twoFactor.codes.copyFailed')}
      </p>
    </div>
  );
}

interface CodeDialogProps {
  open: boolean;
  title: string;
  text: string;
  action: string;
  accepts: 'totp' | 'any';
  pending: boolean;
  error: unknown;
  /** The end of a lock the status already reported, so submit waits before the server has to refuse. */
  lockedUntil: Date | null;
  onSubmit: (code: string) => void;
  onCancel: () => void;
}

/**
 * Asks for a code before a sensitive action, as a native modal dialog: focus starts on the field,
 * Escape cancels, and focus goes back to whatever opened it.
 */
function CodeDialog({
  open,
  title,
  text,
  action,
  accepts,
  pending,
  error,
  lockedUntil,
  onSubmit,
  onCancel,
}: Readonly<CodeDialogProps>) {
  const { t } = useTranslation();
  const ids = useId();
  const dialogRef = useRef<HTMLDialogElement>(null);
  const inputRef = useRef<HTMLInputElement>(null);
  const openerRef = useRef<HTMLElement | null>(null);
  const [code, setCode] = useState('');
  const [shapeError, setShapeError] = useState(false);
  const lockActive = useLockActive(lockOf(error) ?? lockedUntil);

  useEffect(() => {
    const dialog = dialogRef.current;
    if (!dialog) return;
    if (open) {
      if (!dialog.open) {
        setCode('');
        setShapeError(false);
        openerRef.current =
          document.activeElement instanceof HTMLElement ? document.activeElement : null;
        dialog.showModal();
        inputRef.current?.focus();
      }
      return;
    }
    if (dialog.open) dialog.close();
    setCode('');
    const opener = openerRef.current;
    openerRef.current = null;
    if (opener?.isConnected) opener.focus();
  }, [open]);

  useEffect(() => {
    if (open && error) {
      setCode('');
      inputRef.current?.focus();
    }
  }, [open, error]);

  const lockEnded = Boolean(error) && totpFailureFrom(error).kind === 'locked' && !lockActive;
  const refusal = error && !lockEnded ? refusalText(t, error) : null;
  const errorId = `${ids}-error`;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    if (pending || lockActive) return;
    if (!matchesShape(code, accepts)) {
      setShapeError(true);
      inputRef.current?.focus();
      return;
    }
    setShapeError(false);
    onSubmit(code.trim());
  };

  return (
    <dialog
      ref={dialogRef}
      className="two-factor-dialog"
      aria-labelledby={`${ids}-title`}
      aria-describedby={`${ids}-text`}
      onCancel={(event) => {
        event.preventDefault();
        onCancel();
      }}
      // A browser may close the dialog on a repeated Escape despite `preventDefault`.
      onClose={() => {
        if (open) onCancel();
      }}
    >
      <form onSubmit={submit} noValidate>
        <h2 id={`${ids}-title`}>{title}</h2>
        <p id={`${ids}-text`}>{text}</p>
        <CodeField
          id={`${ids}-code`}
          label={t('auth.twoFactor.dialog.codeLabel')}
          value={code}
          onChange={(value) => {
            setCode(value);
            setShapeError(false);
          }}
          accepts={accepts}
          disabled={pending}
          invalid={shapeError || Boolean(refusal)}
          describedBy={shapeError || refusal ? errorId : undefined}
          inputRef={inputRef}
        />
        {(shapeError || refusal) && (
          <p id={errorId} role="alert" className="form-error">
            {shapeError
              ? t(
                  accepts === 'totp'
                    ? 'auth.twoFactor.errors.totpShape'
                    : 'auth.twoFactor.errors.anyShape',
                )
              : refusal}
          </p>
        )}
        <div className="two-factor-actions two-factor-actions--dialog">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={onCancel}
            disabled={pending}
          >
            {t('auth.twoFactor.dialog.cancel')}
          </button>
          <button
            type="submit"
            className="cortex-btn cortex-btn--primary"
            disabled={pending || lockActive}
            aria-busy={pending}
          >
            {pending ? t('auth.twoFactor.dialog.working') : action}
          </button>
        </div>
      </form>
    </dialog>
  );
}

/** The member's own second factor: setup, recovery codes, regenerate and turn off. */
export function TwoFactorSection() {
  const { t } = useTranslation();
  const titleId = useId();
  const status = useTotpStatusQuery();
  const start = useStartTotpSetupMutation();
  const enable = useEnableTotpMutation();
  const disable = useDisableTotpMutation();
  const regenerate = useRegenerateRecoveryCodesMutation();
  const [view, setView] = useState<View>({ name: 'idle' });
  const [dialog, setDialog] = useState<DialogName | null>(null);
  const [turnedOff, setTurnedOff] = useState(false);

  const data = status.data;
  const statusLock = data?.lockedUntil ? new Date(data.lockedUntil) : null;
  const statusLockActive = useLockActive(statusLock);

  const askDialog = (name: DialogName) => {
    regenerate.reset();
    disable.reset();
    setTurnedOff(false);
    setDialog(name);
  };

  const beginSetup = () => {
    setTurnedOff(false);
    enable.reset();
    start.mutate(undefined, {
      onSuccess: (setup) => setView({ name: 'scan', setup }),
      onError: (error) => {
        // Already on: the status was stale, so the panel redraws from the server's answer.
        if (error instanceof ApiError && error.problem?.code === 'TOTP_ALREADY_ENABLED') {
          void status.refetch();
        }
      },
    });
  };

  const cancelSetup = () => {
    start.reset();
    enable.reset();
    setView({ name: 'idle' });
  };

  const confirmSetup = (code: string) => {
    enable.mutate(code, {
      onSuccess: (result) => {
        start.reset();
        enable.reset();
        setView({ name: 'codes', codes: result.recoveryCodes, origin: 'enabled' });
      },
      onError: (error) => {
        const problemCode = error instanceof ApiError ? error.problem?.code : undefined;
        if (problemCode === 'TOTP_NOT_SET_UP' || problemCode === 'TOTP_ALREADY_ENABLED') {
          start.reset();
          setView({ name: 'idle' });
          void status.refetch();
        }
      },
    });
  };

  const submitRegenerate = (code: string) =>
    regenerate.mutate(code, {
      onSuccess: (result) => {
        regenerate.reset();
        setDialog(null);
        setView({ name: 'codes', codes: result.recoveryCodes, origin: 'regenerated' });
      },
    });

  const submitDisable = (code: string) =>
    disable.mutate(code, {
      onSuccess: () => {
        setDialog(null);
        setTurnedOff(true);
      },
    });

  let body;
  if (view.name === 'codes') {
    body = (
      <RecoveryCodes
        codes={view.codes}
        origin={view.origin}
        onDone={() => setView({ name: 'idle' })}
      />
    );
  } else if (view.name === 'scan') {
    body = (
      <SetupScan
        setup={view.setup}
        pending={enable.isPending}
        error={enable.error}
        onConfirm={confirmSetup}
        onCancel={cancelSetup}
      />
    );
  } else if (status.isLoading) {
    body = <output>{t('auth.twoFactor.loading')}</output>;
  } else if (status.isError || !data) {
    body = (
      <p role="alert" className="form-error">
        {t('auth.twoFactor.loadError')}{' '}
        <button type="button" onClick={() => void status.refetch()}>
          {t('auth.twoFactor.retry')}
        </button>
      </p>
    );
  } else if (data.enabled) {
    body = (
      <>
        <p className="two-factor-state two-factor-state--on">{t('auth.twoFactor.statusOn')}</p>
        <p>{t('auth.twoFactor.codesLeft', { count: data.recoveryCodesLeft })}</p>
        {statusLockActive && statusLock && (
          <p role="status" className="two-factor-note">
            {t('auth.twoFactor.errors.locked', {
              time: lockEndText(statusLock, activeLocale()),
            })}
          </p>
        )}
        {data.required && <p className="two-factor-note">{t('auth.twoFactor.requiredNote')}</p>}
        <div className="two-factor-actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={() => askDialog('regenerate')}
          >
            {t('auth.twoFactor.regenerate.open')}
          </button>
          {!data.required && (
            <button
              type="button"
              className="cortex-btn cortex-btn--danger"
              onClick={() => askDialog('disable')}
            >
              {t('auth.twoFactor.disable.open')}
            </button>
          )}
        </div>
      </>
    );
  } else {
    body = (
      <>
        <p className="two-factor-state">{t('auth.twoFactor.statusOff')}</p>
        {data.required && <p className="two-factor-note">{t('auth.twoFactor.requiredHint')}</p>}
        {start.isError && (
          <p role="alert" className="form-error">
            {t('auth.twoFactor.startFailed')}
          </p>
        )}
        <div className="two-factor-actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--primary"
            onClick={beginSetup}
            disabled={start.isPending}
            aria-busy={start.isPending}
          >
            {start.isPending ? t('auth.twoFactor.starting') : t('auth.twoFactor.start')}
          </button>
        </div>
      </>
    );
  }

  return (
    <section className="two-factor" aria-labelledby={titleId}>
      <h2 id={titleId} className="profile-card__title">
        {t('auth.twoFactor.title')}
      </h2>
      <p className="profile-card__hint">{t('auth.twoFactor.hint')}</p>
      {turnedOff && view.name === 'idle' && (
        <p role="status" className="two-factor-note">
          {t('auth.twoFactor.disable.done')}
        </p>
      )}
      {body}

      <CodeDialog
        open={dialog === 'regenerate'}
        title={t('auth.twoFactor.regenerate.title')}
        text={t('auth.twoFactor.regenerate.text')}
        action={t('auth.twoFactor.regenerate.action')}
        accepts="totp"
        pending={regenerate.isPending}
        error={regenerate.error}
        lockedUntil={statusLock}
        onSubmit={submitRegenerate}
        onCancel={() => setDialog(null)}
      />
      <CodeDialog
        open={dialog === 'disable'}
        title={t('auth.twoFactor.disable.title')}
        text={t('auth.twoFactor.disable.text')}
        action={t('auth.twoFactor.disable.action')}
        accepts="any"
        pending={disable.isPending}
        error={disable.error}
        lockedUntil={statusLock}
        onSubmit={submitDisable}
        onCancel={() => setDialog(null)}
      />
    </section>
  );
}
