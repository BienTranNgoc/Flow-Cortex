import { useEffect, useId, useRef, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { ApiError, StepUpRequestPurpose } from '@flow-cortex/api-client';
import type { ApiTokenView, CreatedApiToken } from '@flow-cortex/api-client';
import { formatDateTime } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { StepUpDialog } from './StepUpDialog';
import {
  useApiTokensQuery,
  useCreateApiTokenMutation,
  useRevokeApiTokenMutation,
} from './useApiTokens';
import './ApiTokensSection.css';

const LIFETIME_DAYS = [30, 90, 180, 365] as const;
const DEFAULT_LIFETIME_DAYS = 90;
const NAME_MAX = 128;
// Mirrors the server default of `max-live-per-user`; the server stays the authority.
const MAX_ACTIVE_TOKENS = 10;
const DATE_FORMAT: Intl.DateTimeFormatOptions = { dateStyle: 'medium' };
const COPIED_RESET_MS = 4000;

type CopyState = 'idle' | 'copied' | 'failed';

function problemCodeOf(error: unknown): string | undefined {
  return error instanceof ApiError ? error.problem?.code : undefined;
}

function TokenReveal({
  created,
  onDone,
}: Readonly<{ created: CreatedApiToken; onDone: () => void }>) {
  const { t } = useTranslation();
  const fieldId = useId();
  const doneRef = useRef<HTMLButtonElement>(null);
  const [copy, setCopy] = useState<CopyState>('idle');

  useEffect(() => {
    doneRef.current?.focus();
  }, []);

  useEffect(() => {
    if (copy !== 'copied') return undefined;
    const id = setTimeout(() => setCopy('idle'), COPIED_RESET_MS);
    return () => clearTimeout(id);
  }, [copy]);

  const copyToken = async () => {
    try {
      await navigator.clipboard.writeText(created.token);
      setCopy('copied');
    } catch {
      setCopy('failed');
    }
  };

  return (
    <div className="api-tokens-reveal" role="group" aria-labelledby={`${fieldId}-title`}>
      <h3 id={`${fieldId}-title`}>{t('auth.apiTokens.reveal.title', { name: created.name })}</h3>
      <p className="api-tokens-reveal__warning">{t('auth.apiTokens.reveal.warning')}</p>
      <div className="field">
        <label htmlFor={fieldId}>{t('auth.apiTokens.reveal.tokenLabel')}</label>
        <input id={fieldId} readOnly value={created.token} onFocus={(e) => e.target.select()} />
      </div>
      <div className="api-tokens-actions">
        <button type="button" className="cortex-btn cortex-btn--ghost" onClick={() => void copyToken()}>
          {t('auth.apiTokens.reveal.copy')}
        </button>
        <button
          ref={doneRef}
          type="button"
          className="cortex-btn cortex-btn--primary"
          onClick={onDone}
        >
          {t('auth.apiTokens.reveal.done')}
        </button>
      </div>
      <p className="api-tokens-reveal__copied" role="status" aria-live="polite">
        {copy === 'copied' && t('auth.apiTokens.reveal.copied')}
        {copy === 'failed' && t('auth.apiTokens.reveal.copyFailed')}
      </p>
    </div>
  );
}

function TokenRow({
  token,
  confirming,
  revoking,
  onAskRevoke,
  onCancelRevoke,
  onRevoke,
}: Readonly<{
  token: ApiTokenView;
  confirming: boolean;
  revoking: boolean;
  onAskRevoke: () => void;
  onCancelRevoke: () => void;
  onRevoke: () => void;
}>) {
  const { t } = useTranslation();
  const cancelRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (confirming) cancelRef.current?.focus();
  }, [confirming]);

  const locale = activeLocale();
  return (
    <li className="api-tokens-row">
      <div className="api-tokens-row__main">
        <span className="api-tokens-row__name">{token.name}</span>
        {token.expired && <span className="api-tokens-row__pill">{t('auth.apiTokens.expired')}</span>}
        <span className="api-tokens-row__meta">
          {t('auth.apiTokens.createdOn', {
            date: formatDateTime(token.createdAt, locale, DATE_FORMAT),
          })}
          {' · '}
          {t(token.expired ? 'auth.apiTokens.expiredOn' : 'auth.apiTokens.expiresOn', {
            date: formatDateTime(token.expiresAt, locale, DATE_FORMAT),
          })}
          {' · '}
          {token.lastUsedAt
            ? t('auth.apiTokens.lastUsedOn', {
                date: formatDateTime(token.lastUsedAt, locale, DATE_FORMAT),
              })
            : t('auth.apiTokens.neverUsed')}
        </span>
      </div>
      {confirming ? (
        <div
          className="api-tokens-row__confirm"
          role="group"
          aria-label={t('auth.apiTokens.revokeConfirm', { name: token.name })}
        >
          <p>{t('auth.apiTokens.revokeConfirm', { name: token.name })}</p>
          <button
            ref={cancelRef}
            type="button"
            className="cortex-btn cortex-btn--ghost"
            onClick={onCancelRevoke}
            disabled={revoking}
          >
            {t('auth.apiTokens.revokeKeep')}
          </button>
          <button
            type="button"
            className="cortex-btn cortex-btn--danger"
            onClick={onRevoke}
            disabled={revoking}
            aria-busy={revoking}
          >
            {revoking ? t('auth.apiTokens.revoking') : t('auth.apiTokens.revokeConfirmAction')}
          </button>
        </div>
      ) : (
        <button
          id={`api-token-revoke-${token.id}`}
          type="button"
          className="cortex-btn cortex-btn--ghost"
          onClick={onAskRevoke}
          aria-label={t('auth.apiTokens.revokeNamed', { name: token.name })}
        >
          {t('auth.apiTokens.revoke')}
        </button>
      )}
    </li>
  );
}

/** Lists the admin's personal API tokens and creates one behind a password re-check. */
export function ApiTokensSection() {
  const { t } = useTranslation();
  const ids = useId();
  const tokens = useApiTokensQuery(true);
  const createMutation = useCreateApiTokenMutation();
  const revokeMutation = useRevokeApiTokenMutation();

  const [name, setName] = useState('');
  const [days, setDays] = useState<number>(DEFAULT_LIFETIME_DAYS);
  const [nameError, setNameError] = useState(false);
  const [askingPassword, setAskingPassword] = useState(false);
  const [created, setCreated] = useState<CreatedApiToken | null>(null);
  const [createError, setCreateError] = useState<string | null>(null);
  const [confirmingId, setConfirmingId] = useState<string | null>(null);
  const [revokeError, setRevokeError] = useState<string | null>(null);

  const listTitleRef = useRef<HTMLHeadingElement>(null);
  const nameId = `${ids}-name`;
  const daysId = `${ids}-days`;

  const closeConfirm = (id: string) => {
    setConfirmingId(null);
    // The revoke button is mounted again by the render this state change causes.
    setTimeout(() => document.getElementById(`api-token-revoke-${id}`)?.focus(), 0);
  };

  const onSubmit = (event: FormEvent) => {
    event.preventDefault();
    const trimmed = name.trim();
    if (trimmed === '' || trimmed.length > NAME_MAX) {
      setNameError(true);
      return;
    }
    setNameError(false);
    setCreateError(null);
    setAskingPassword(true);
  };

  const onProof = (stepUpProof: string) => {
    setAskingPassword(false);
    createMutation.mutate(
      { name: name.trim(), expiresInDays: days, stepUpProof },
      {
        onSuccess: (token) => {
          setCreated(token);
          setName('');
          setDays(DEFAULT_LIFETIME_DAYS);
          // The raw token lives in this component's state only, not in the mutation cache.
          createMutation.reset();
        },
        onError: (error) => {
          const code = problemCodeOf(error);
          if (code === 'PAT_LIMIT_REACHED') setCreateError(t('auth.apiTokens.limitReached', { max: MAX_ACTIVE_TOKENS }));
          else if (code === 'STEP_UP_REQUIRED') setCreateError(t('auth.apiTokens.stepUpRequired'));
          else setCreateError(t('auth.apiTokens.createFailed'));
        },
      },
    );
  };

  const onRevoke = (token: ApiTokenView) => {
    setRevokeError(null);
    revokeMutation.mutate(token.id, {
      onSuccess: () => {
        setConfirmingId(null);
        listTitleRef.current?.focus();
      },
      onError: () => setRevokeError(t('auth.apiTokens.revokeFailed', { name: token.name })),
    });
  };

  return (
    <section className="api-tokens" aria-labelledby={`${ids}-title`}>
      <h2 id={`${ids}-title`} className="profile-card__title">
        {t('auth.apiTokens.title')}
      </h2>
      <p className="profile-card__hint">{t('auth.apiTokens.hint')}</p>

      {created ? (
        <TokenReveal created={created} onDone={() => setCreated(null)} />
      ) : (
        <form className="api-tokens-form" onSubmit={onSubmit} noValidate>
          <div className="field">
            <label htmlFor={nameId}>{t('auth.apiTokens.nameLabel')}</label>
            <input
              id={nameId}
              value={name}
              maxLength={NAME_MAX}
              onChange={(event) => setName(event.target.value)}
              placeholder={t('auth.apiTokens.namePlaceholder')}
              aria-invalid={nameError || undefined}
              aria-describedby={nameError ? `${nameId}-error` : undefined}
              autoComplete="off"
            />
            {nameError && (
              <p id={`${nameId}-error`} role="alert" className="form-error">
                {t('auth.apiTokens.nameRequired')}
              </p>
            )}
          </div>
          <div className="field">
            <label htmlFor={daysId}>{t('auth.apiTokens.lifetimeLabel')}</label>
            <select
              id={daysId}
              value={days}
              onChange={(event) => setDays(Number(event.target.value))}
            >
              {LIFETIME_DAYS.map((option) => (
                <option key={option} value={option}>
                  {t('auth.apiTokens.lifetimeDays', { days: option })}
                </option>
              ))}
            </select>
          </div>
          {createError && (
            <p role="alert" className="form-error">
              {createError}
            </p>
          )}
          <div className="api-tokens-actions">
            <button
              type="submit"
              className="cortex-btn cortex-btn--primary"
              disabled={createMutation.isPending}
              aria-busy={createMutation.isPending}
            >
              {createMutation.isPending ? t('auth.apiTokens.creating') : t('auth.apiTokens.create')}
            </button>
          </div>
        </form>
      )}

      <StepUpDialog
        purpose={StepUpRequestPurpose.PAT_CREATE}
        open={askingPassword}
        onProof={onProof}
        onCancel={() => setAskingPassword(false)}
      />

      <h3 ref={listTitleRef} tabIndex={-1} className="api-tokens-list-title">{t('auth.apiTokens.listTitle')}</h3>
      {tokens.isLoading && <output>{t('auth.apiTokens.loading')}</output>}
      {tokens.isError && (
        <p role="alert" className="form-error">
          {t('auth.apiTokens.loadError')}{' '}
          <button type="button" onClick={() => void tokens.refetch()}>
            {t('auth.apiTokens.retry')}
          </button>
        </p>
      )}
      {tokens.data && tokens.data.length === 0 && (
        <p className="api-tokens-empty">{t('auth.apiTokens.empty')}</p>
      )}
      {revokeError && (
        <p role="alert" className="form-error">
          {revokeError}
        </p>
      )}
      {tokens.data && tokens.data.length > 0 && (
        <ul className="api-tokens-list">
          {tokens.data.map((token) => (
            <TokenRow
              key={token.id}
              token={token}
              confirming={confirmingId === token.id}
              revoking={revokeMutation.isPending && confirmingId === token.id}
              onAskRevoke={() => {
                setRevokeError(null);
                setConfirmingId(token.id);
              }}
              onCancelRevoke={() => closeConfirm(token.id)}
              onRevoke={() => onRevoke(token)}
            />
          ))}
        </ul>
      )}
    </section>
  );
}
