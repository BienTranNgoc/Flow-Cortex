import type {
  AiProviderCategory,
  AiProviderOverview,
  AiProviderView,
  ProbeResult,
} from '@flow-cortex/api-client';
import { useState } from 'react';
import { useTranslation } from 'react-i18next';
import { EmptyState } from '../../app/EmptyState';
import { StatusPill, type StatusTone } from '../../app/StatusPill';
import { ProbeOutcome } from './ProbeOutcome';
import { useTestProviderMutation } from './useAiProviders';

type TestOutcome = ProbeResult | 'failed';

const STATE_TONE: Record<string, StatusTone> = {
  active: 'success',
  inactive: 'muted',
  unregistered: 'warning',
  not_applicable: 'info',
};

const KNOWN_STATES: ReadonlySet<string> = new Set(Object.keys(STATE_TONE));

interface ProvidersTabProps {
  readonly overview: AiProviderOverview | undefined;
  readonly isPending: boolean;
  readonly isError: boolean;
  readonly onRetry: () => void;
  readonly canUpdate: boolean;
}

function ProviderCard({
  provider,
  canUpdate,
}: Readonly<{ provider: AiProviderView; canUpdate: boolean }>) {
  const { t } = useTranslation();
  const test = useTestProviderMutation();
  const [outcome, setOutcome] = useState<TestOutcome | undefined>();
  const key = provider.key ?? '';
  const testing = test.isPending;
  const onTest = () => {
    if (!provider.key) return;
    test.mutate(provider.key, {
      onSuccess: setOutcome,
      onError: () => setOutcome('failed'),
    });
  };
  const missing = provider.missingSettings ?? [];
  const slots = provider.slots ?? [];

  return (
    <article className="providers-card" aria-label={key}>
      <header className="providers-card__head">
        <h3>{key}</h3>
        <div className="providers-card__badges">
          <StatusPill
            tone={provider.configured ? 'success' : 'warning'}
            label={t(
              provider.configured
                ? 'providers.overview.configured'
                : 'providers.overview.notConfigured',
            )}
          />
          <StatusPill
            tone={provider.liveCheckable ? 'info' : 'muted'}
            label={t(
              provider.liveCheckable
                ? 'providers.overview.liveCheck'
                : 'providers.overview.noLiveCheck',
            )}
          />
        </div>
      </header>
      {!provider.configured && missing.length > 0 && (
        <p className="providers-note">
          {t('providers.overview.missing')} <code>{missing.join(', ')}</code>
        </p>
      )}
      <h4>{t('providers.overview.slots')}</h4>
      {slots.length === 0 && <p className="providers-note">{t('providers.overview.noSlots')}</p>}
      {slots.map((slot) => (
        <div key={slot.name} className="providers-slot">
          <p className="providers-slot__name">{slot.name}</p>
          <ul className="providers-slot__models">
            {(slot.models ?? []).map((model) => {
              const status = model.status ?? '';
              const known = KNOWN_STATES.has(status);
              return (
                <li key={model.modelKey}>
                  <code>{model.modelKey}</code>
                  <StatusPill
                    tone={STATE_TONE[status] ?? 'muted'}
                    label={known ? t(`providers.overview.state.${status}`) : status}
                  />
                </li>
              );
            })}
          </ul>
        </div>
      ))}
      {canUpdate && (
        <div className="providers-card__actions">
          <button
            type="button"
            className="cortex-admin-btn cortex-admin-btn--sm"
            aria-label={t('providers.overview.testFor', { key })}
            disabled={testing}
            onClick={onTest}
          >
            {testing ? t('providers.overview.testing') : t('providers.overview.test')}
          </button>
        </div>
      )}
      <ProbeOutcome
        result={outcome === 'failed' ? undefined : outcome}
        failed={outcome === 'failed'}
      />
    </article>
  );
}

function CategorySection({
  group,
  canUpdate,
}: Readonly<{ group: AiProviderCategory; canUpdate: boolean }>) {
  const { t } = useTranslation();
  const category = group.category ?? '';
  const heading = t(`providers.overview.category.${category}`, { defaultValue: category });
  return (
    <section className="providers-category" aria-label={heading}>
      <h2>{heading}</h2>
      <div className="providers-grid">
        {(group.providers ?? []).map((provider) => (
          <ProviderCard key={provider.key} provider={provider} canUpdate={canUpdate} />
        ))}
      </div>
    </section>
  );
}

/** The registered providers by category, each with its configuration, model slots and a connectivity test. */
export function ProvidersTab({
  overview,
  isPending,
  isError,
  onRetry,
  canUpdate,
}: ProvidersTabProps) {
  const { t } = useTranslation();
  const categories = overview?.categories ?? [];

  return (
    <div className="providers-tab">
      {isError && !overview && (
        <div className="cortex-admin-error">
          <p role="alert">{t('providers.loadError')}</p>
          <button type="button" className="cortex-admin-btn" onClick={onRetry}>
            {t('providers.retry')}
          </button>
        </div>
      )}
      {isPending && <output className="cortex-admin-loading">{t('providers.loading')}</output>}
      {overview && categories.length === 0 && <EmptyState title={t('providers.overview.empty')} />}
      {categories.map((group) => (
        <CategorySection key={group.category} group={group} canUpdate={canUpdate} />
      ))}
    </div>
  );
}
