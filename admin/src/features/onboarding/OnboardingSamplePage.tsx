import {
  ApiError,
  authHeaders,
  getAdminOnboardingSample,
  updateAdminOnboardingSample,
  updateAdminOnboardingSampleTranslation,
  type AdminOnboardingSample,
  type AdminOnboardingSampleUpdate,
} from '@flow-cortex/api-client';
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { useId, useState, type FormEvent } from 'react';
import { useTranslation } from 'react-i18next';
import { usePageTitle } from '../../app/usePageTitle';
import { activeLocale } from '../../lib/i18n';
import { unwrapSupport } from '../support/unwrapSupport';
import { formatVietnamTime } from '../support/vietnamTime';
import './onboardingSample.css';

const SAMPLE_KEY = ['admin', 'onboarding', 'sample'] as const;
const MAX_INPUT = 10_000;
const SOURCE_LOCALES = ['vi', 'en'] as const;

type SourceLocale = (typeof SOURCE_LOCALES)[number];

interface TranslationDraft {
  locale: string;
  sourceHash: string;
  input: string;
}

/** The server's field messages (already in the admin's language) of a 400 or 422 answer. */
function fieldErrors(error: unknown): Record<string, string> {
  return error instanceof ApiError ? (error.problem?.errors ?? {}) : {};
}

function isStale(error: unknown): boolean {
  return error instanceof ApiError && error.status === 409;
}

function isSourceLocale(value: string | null): value is SourceLocale {
  return SOURCE_LOCALES.some((locale) => locale === value);
}

function StatusBlock({ view }: Readonly<{ view: AdminOnboardingSample }>) {
  const { t } = useTranslation();
  const configured = view.workflow !== null;
  return (
    <section className="cortex-admin-panel onboarding-sample-block" aria-labelledby="onboarding-status-heading">
      <h2 id="onboarding-status-heading">{t('onboarding.status.heading')}</h2>
      <p className="onboarding-sample-workflow">
        <span className="onboarding-sample-label">{t('onboarding.status.workflow')}</span>
        <strong>{view.workflow?.name ?? t('onboarding.status.none')}</strong>
        {configured && (
          <span
            className={`cortex-admin-pill ${view.shown ? 'cortex-admin-pill--success' : 'cortex-admin-pill--warning'}`}
          >
            {t(view.shown ? 'onboarding.status.shown' : 'onboarding.status.notShown')}
          </span>
        )}
      </p>
      {configured && !view.shown && view.reason && (
        <p className="onboarding-sample-reason">
          {t(`onboarding.reason.${view.reason}`, { defaultValue: view.reason })}
        </p>
      )}
      {view.updatedBy && (
        <p className="onboarding-sample-meta">
          {t('onboarding.status.changedBy', {
            email: view.updatedBy,
            at: formatVietnamTime(view.updatedAt),
          })}
        </p>
      )}
    </section>
  );
}

interface SampleFormProps {
  view: AdminOnboardingSample;
  busy: boolean;
  saving: boolean;
  clearing: boolean;
  error: unknown;
  onSubmit: (body: AdminOnboardingSampleUpdate) => void;
}

function SampleForm({ view, busy, saving, clearing, error, onSubmit }: Readonly<SampleFormProps>) {
  const { t } = useTranslation();
  const ids = { workflow: useId(), input: useId(), locale: useId() };
  const [workflowId, setWorkflowId] = useState(view.workflow?.id ?? '');
  const [sampleInput, setSampleInput] = useState(view.sampleInput ?? '');
  const [sourceLocale, setSourceLocale] = useState<SourceLocale>(
    isSourceLocale(view.sourceLocale) ? view.sourceLocale : activeLocale(),
  );
  const [missingId, setMissingId] = useState(false);
  const serverErrors = fieldErrors(error);
  const errors = {
    workflowId: missingId ? t('onboarding.form.workflowRequired') : serverErrors.workflowId,
    sampleInput: serverErrors.sampleInput,
    sourceLocale: serverErrors.sourceLocale,
  };
  const generalFailure = Boolean(error) && Object.keys(serverErrors).length === 0;

  const submit = (event: FormEvent) => {
    event.preventDefault();
    const id = workflowId.trim();
    setMissingId(id === '');
    if (id === '') return;
    onSubmit({ workflowId: id, sampleInput, sourceLocale });
  };

  const describe = (field: keyof typeof errors, hint?: string) =>
    [errors[field] ? `${ids.workflow}-${field}-error` : undefined, hint].filter(Boolean).join(' ') ||
    undefined;

  return (
    <section className="cortex-admin-panel onboarding-sample-block" aria-labelledby="onboarding-form-heading">
      <h2 id="onboarding-form-heading">{t('onboarding.form.heading')}</h2>
      <form className="onboarding-sample-form" onSubmit={submit} noValidate>
        <div className="cortex-admin-field">
          <label htmlFor={ids.workflow}>{t('onboarding.form.workflowId')}</label>
          <input
            id={ids.workflow}
            type="text"
            value={workflowId}
            autoComplete="off"
            spellCheck={false}
            aria-invalid={errors.workflowId ? true : undefined}
            aria-describedby={describe('workflowId', `${ids.workflow}-hint`)}
            onChange={(event) => {
              setWorkflowId(event.target.value);
              setMissingId(false);
            }}
          />
          <small id={`${ids.workflow}-hint`} className="onboarding-sample-hint">
            {t('onboarding.form.workflowIdHint')}
          </small>
          {errors.workflowId && (
            <p id={`${ids.workflow}-workflowId-error`} className="cortex-admin-form-error" role="alert">
              {errors.workflowId}
            </p>
          )}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={ids.input}>{t('onboarding.form.sampleInput')}</label>
          <input
            id={ids.input}
            type="text"
            value={sampleInput}
            maxLength={MAX_INPUT}
            aria-invalid={errors.sampleInput ? true : undefined}
            aria-describedby={describe('sampleInput', `${ids.input}-hint`)}
            onChange={(event) => setSampleInput(event.target.value)}
          />
          <small id={`${ids.input}-hint`} className="onboarding-sample-hint">
            {t('onboarding.form.sampleInputHint')}{' '}
            <span className="onboarding-sample-counter">
              {t('onboarding.form.counter', { used: sampleInput.length, max: MAX_INPUT })}
            </span>
          </small>
          {errors.sampleInput && (
            <p id={`${ids.workflow}-sampleInput-error`} className="cortex-admin-form-error" role="alert">
              {errors.sampleInput}
            </p>
          )}
        </div>

        <div className="cortex-admin-field">
          <label htmlFor={ids.locale}>{t('onboarding.form.sourceLocale')}</label>
          <select
            id={ids.locale}
            value={sourceLocale}
            aria-invalid={errors.sourceLocale ? true : undefined}
            aria-describedby={describe('sourceLocale')}
            onChange={(event) => setSourceLocale(event.target.value as SourceLocale)}
          >
            {SOURCE_LOCALES.map((locale) => (
              <option key={locale} value={locale}>
                {t(`onboarding.locale.${locale}`)}
              </option>
            ))}
          </select>
          {errors.sourceLocale && (
            <p id={`${ids.workflow}-sourceLocale-error`} className="cortex-admin-form-error" role="alert">
              {errors.sourceLocale}
            </p>
          )}
        </div>

        {generalFailure && <p role="alert" className="cortex-admin-form-error">{t('onboarding.common.saveFailed')}</p>}

        <div className="onboarding-sample-actions">
          <button type="submit" className="cortex-admin-btn cortex-admin-btn--primary" disabled={busy}>
            {t(saving ? 'onboarding.form.saving' : 'onboarding.form.save')}
          </button>
          <button
            type="button"
            className="cortex-admin-btn"
            disabled={busy}
            onClick={() => onSubmit({ workflowId: null })}
          >
            {t(clearing ? 'onboarding.form.clearing' : 'onboarding.form.clear')}
          </button>
        </div>
      </form>
    </section>
  );
}

interface TranslationFieldProps {
  locale: string;
  value: string;
  busy: boolean;
  saving: boolean;
  saved: boolean;
  error: unknown;
  reloading: boolean;
  onChange: (value: string) => void;
  onSave: () => void;
  onReload: () => void;
}

function TranslationField(props: Readonly<TranslationFieldProps>) {
  const { locale, value, busy, saving, saved, error, reloading, onChange, onSave, onReload } = props;
  const { t } = useTranslation();
  const inputId = useId();
  const stale = isStale(error);
  const serverErrors = fieldErrors(error);
  const message = stale
    ? undefined
    : (serverErrors.input ??
      serverErrors.locale ??
      (error ? ((error instanceof ApiError && error.problem?.detail) || t('onboarding.common.saveFailed')) : undefined));
  const language = t(`onboarding.locale.${locale}`, { defaultValue: locale });

  return (
    <div className="onboarding-sample-translation">
      <div className="cortex-admin-field">
        <label htmlFor={inputId}>{t('onboarding.translations.field', { language })}</label>
        <input
          id={inputId}
          type="text"
          value={value}
          maxLength={MAX_INPUT}
          lang={locale}
          aria-invalid={message ? true : undefined}
          aria-describedby={`${inputId}-hint${message ? ` ${inputId}-error` : ''}${stale ? ` ${inputId}-stale` : ''}`}
          onChange={(event) => onChange(event.target.value)}
        />
        <small id={`${inputId}-hint`} className="onboarding-sample-hint">
          {t('onboarding.translations.fieldHint')}
        </small>
        {message && (
          <p id={`${inputId}-error`} className="cortex-admin-form-error" role="alert">
            {message}
          </p>
        )}
      </div>
      {stale && (
        <div id={`${inputId}-stale`} className="onboarding-sample-stale" role="alert">
          <p>{t('onboarding.translations.stale')}</p>
          <button type="button" className="cortex-admin-btn cortex-admin-btn--sm" disabled={reloading} onClick={onReload}>
            {t('onboarding.translations.reload')}
          </button>
        </div>
      )}
      <div className="onboarding-sample-actions">
        <button type="button" className="cortex-admin-btn" disabled={busy} onClick={onSave}>
          {t(saving ? 'onboarding.translations.saving' : 'onboarding.translations.save')}
        </button>
        {saved && <output className="cortex-admin-form-success">{t('onboarding.translations.saved')}</output>}
      </div>
    </div>
  );
}

export function OnboardingSamplePage() {
  const { t } = useTranslation();
  usePageTitle(t('onboarding.title'));
  const queryClient = useQueryClient();
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const sampleQuery = useQuery({
    queryKey: SAMPLE_KEY,
    queryFn: () => unwrapSupport(getAdminOnboardingSample(authHeaders())),
  });
  const save = useMutation({
    mutationFn: (body: AdminOnboardingSampleUpdate) =>
      unwrapSupport(updateAdminOnboardingSample(body, authHeaders())),
    onSuccess: (view) => {
      queryClient.setQueryData(SAMPLE_KEY, view);
      setDrafts({});
    },
  });
  const translate = useMutation({
    mutationFn: ({ locale, sourceHash, input }: TranslationDraft) =>
      unwrapSupport(updateAdminOnboardingSampleTranslation(locale, { sourceHash, input }, authHeaders())),
    onSuccess: (view, { locale }) => {
      queryClient.setQueryData(SAMPLE_KEY, view);
      setDrafts((current) => Object.fromEntries(Object.entries(current).filter(([key]) => key !== locale)));
    },
    onError: (error) => {
      if (error instanceof ApiError && error.status === 404) {
        void queryClient.invalidateQueries({ queryKey: SAMPLE_KEY });
      }
    },
  });
  const view = sampleQuery.data;
  const busy = save.isPending || translate.isPending;
  const sampleLanguage: string = view?.sourceLocale ?? '';
  const sourceHash = view?.sourceHash ?? null;

  const reload = async () => {
    const result = await sampleQuery.refetch();
    if (result.isSuccess) translate.reset();
  };

  return (
    <main>
      <div className="cortex-admin-page-heading">
        <div>
          <p className="cortex-admin-eyebrow">{t('onboarding.eyebrow')}</p>
          <h1>{t('onboarding.title')}</h1>
          <p className="cortex-admin-page-description">{t('onboarding.description')}</p>
        </div>
      </div>

      {sampleQuery.isPending && (
        <div className="onboarding-sample-loading">
          <div className="onboarding-sample-skeleton" aria-hidden="true" />
          <div className="onboarding-sample-skeleton" aria-hidden="true" />
          <output>{t('onboarding.common.loading')}</output>
        </div>
      )}
      {sampleQuery.isError && !view && (
        <div className="cortex-admin-error">
          <p>{t('onboarding.common.loadFailed')}</p>
          <button type="button" className="cortex-admin-btn" onClick={() => sampleQuery.refetch()}>
            {t('onboarding.common.retry')}
          </button>
        </div>
      )}
      {view && (
        <div className="onboarding-sample">
          <StatusBlock view={view} />
          <SampleForm
            key={`${view.workflow?.id ?? ''}|${view.sampleInput ?? ''}|${sampleLanguage}|${view.updatedAt}`}
            view={view}
            busy={busy}
            saving={save.isPending && save.variables?.workflowId !== null}
            clearing={save.isPending && save.variables?.workflowId === null}
            error={save.error}
            onSubmit={(body) => save.mutate(body)}
          />
          <section
            className="cortex-admin-panel onboarding-sample-block"
            aria-labelledby="onboarding-translations-heading"
          >
            <h2 id="onboarding-translations-heading">{t('onboarding.translations.heading')}</h2>
            <p className="onboarding-sample-meta">{t('onboarding.translations.description')}</p>
            {sourceHash === null || Object.keys(view.translations).length === 0 ? (
              <p className="onboarding-sample-meta">{t('onboarding.translations.empty')}</p>
            ) : (
              Object.keys(view.translations).map((locale) => {
                const own = translate.variables?.locale === locale;
                return (
                  <TranslationField
                    key={locale}
                    locale={locale}
                    value={drafts[locale] ?? view.translations[locale] ?? ''}
                    busy={busy}
                    saving={translate.isPending && own}
                    saved={translate.isSuccess && own}
                    error={own ? translate.error : undefined}
                    reloading={sampleQuery.isFetching}
                    onChange={(value) => {
                      setDrafts((current) => ({ ...current, [locale]: value }));
                      if (own && !translate.isPending && !isStale(translate.error)) translate.reset();
                    }}
                    onSave={() =>
                      translate.mutate({
                        locale,
                        sourceHash,
                        input: drafts[locale] ?? view.translations[locale] ?? '',
                      })
                    }
                    onReload={reload}
                  />
                );
              })
            )}
          </section>
        </div>
      )}
    </main>
  );
}
