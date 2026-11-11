import { ApiError } from '@flow-cortex/api-client';
import type { AdminPhoneVerificationStatus } from '@flow-cortex/api-client';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import { StatusPill, type StatusTone } from '../../app/StatusPill';
import { usePageTitle } from '../../app/usePageTitle';
import { formatNumber } from '../../lib/format';
import { activeLocale, type Locale } from '../../lib/i18n';
import { usePhoneVerificationStatus } from './usePhoneVerificationStatus';
import './phoneVerification.css';

interface StatusRow {
  readonly id: string;
  readonly label: string;
  readonly value: string;
  readonly tone?: StatusTone;
}

function switchRow(id: string, label: string, on: boolean, t: TFunction): StatusRow {
  return {
    id,
    label,
    value: t(on ? 'phoneVerification.on' : 'phoneVerification.off'),
    tone: on ? 'success' : 'muted',
  };
}

function rowsOf(status: AdminPhoneVerificationStatus, locale: Locale, t: TFunction) {
  const noSender = status.smsSender === 'none';
  const known = ['none', 'outbox', 'custom'].includes(status.smsSender);
  const sender = known ? t(`phoneVerification.sender.${status.smsSender}`) : status.smsSender;
  return [
    switchRow('verification', t('phoneVerification.fields.verification'), status.verificationEnabled, t),
    {
      id: 'sender',
      label: t('phoneVerification.fields.sender'),
      value: sender,
      tone: noSender ? 'warning' : 'info',
    },
    {
      id: 'hashSecret',
      label: t('phoneVerification.fields.hashSecret'),
      value: t(
        status.hashSecretConfigured
          ? 'phoneVerification.hashSecret.configured'
          : 'phoneVerification.hashSecret.missing',
      ),
      tone: status.hashSecretConfigured ? 'success' : 'warning',
    },
    switchRow('gate', t('phoneVerification.fields.gate'), status.gateEnabled, t),
    {
      id: 'dailyBudget',
      label: t('phoneVerification.fields.dailyBudget'),
      value: formatNumber(status.globalSendsPerDay, locale),
    },
    {
      id: 'sentLast24h',
      label: t('phoneVerification.fields.sentLast24h'),
      value: formatNumber(status.sentLast24h, locale),
    },
  ] satisfies StatusRow[];
}

/** How the phone verification flow is switched and wired in the running backend, read-only. */
export function PhoneVerificationStatusPage() {
  const { t } = useTranslation();
  usePageTitle(t('phoneVerification.title'));
  const query = usePhoneVerificationStatus();
  const { data, error } = query;
  const disabled = error instanceof ApiError && error.status === 503;
  const rows = data ? rowsOf(data, activeLocale(), t) : [];

  return (
    <div>
      <PageHeading
        eyebrow={t('phoneVerification.eyebrow')}
        title={t('phoneVerification.title')}
        description={t('phoneVerification.description')}
      />
      <div className="cortex-admin-panel">
        {disabled && (
          <output className="cortex-admin-disabled-banner">{t('common.shell.list.disabled')}</output>
        )}
        {error && !disabled && (
          <div className="cortex-admin-error">
            <p role="alert">{t('phoneVerification.loadError')}</p>
            <button type="button" className="cortex-admin-btn" onClick={() => query.refetch()}>
              {t('phoneVerification.retry')}
            </button>
          </div>
        )}
        {query.isPending && (
          <output className="cortex-admin-loading">{t('phoneVerification.loading')}</output>
        )}
        {data && error && <p className="cortex-admin-stale">{t('common.shell.list.stale')}</p>}
        {data && (
          <dl className="phone-verification-status" aria-busy={query.isFetching}>
            {rows.map((row) => (
              <div key={row.id} className="phone-verification-status__row">
                <dt>{row.label}</dt>
                <dd>
                  {row.tone ? <StatusPill tone={row.tone} label={row.value} /> : row.value}
                </dd>
              </div>
            ))}
          </dl>
        )}
      </div>
    </div>
  );
}
