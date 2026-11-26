import type { AdminSupportRequestSummarySlaLevel } from '@flow-cortex/api-client';
import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../../lib/format';
import { activeLocale } from '../../../lib/i18n';
import { useSlaSummary, type RequestFilter } from './useAdminRequests';

const BADGE_TONE = { due_soon: 'warning', overdue: 'error' } as const;

/** A text badge for a row at risk; `ok` and `paused` rows show none. The words carry the level, not the colour. */
export function SlaBadge({ level }: Readonly<{ level: AdminSupportRequestSummarySlaLevel }>) {
  const { t } = useTranslation();
  if (level !== 'due_soon' && level !== 'overdue') return null;
  const text = t(`support.requests.sla.level.${level}`);
  return (
    <span
      role="img"
      aria-label={t('support.requests.sla.badgeLabel', { level: text })}
      className={`status-badge request-sla-badge cortex-admin-pill cortex-admin-pill--${BADGE_TONE[level]}`}
    >
      {text}
    </span>
  );
}

/**
 * Overdue and due-soon counts under the inbox filters, with the switch that narrows the list to
 * those rows. It shows nothing while the counts load, when they fail or when both are 0, so a
 * failed summary never blocks the list.
 */
export function SlaBanner({
  filter,
  atRisk,
  onToggle,
}: Readonly<{ filter: RequestFilter; atRisk: boolean; onToggle: (atRisk: boolean) => void }>) {
  const { t } = useTranslation();
  const { data } = useSlaSummary(filter);
  if (!data || (data.overdue === 0 && data.dueSoon === 0)) return null;
  const locale = activeLocale();
  return (
    <div
      className="request-sla-banner"
      role="group"
      aria-label={t('support.requests.sla.bannerLabel')}
    >
      <p className="request-sla-summary">
        {t('support.requests.sla.summary', {
          overdue: formatNumber(data.overdue, locale),
          dueSoon: formatNumber(data.dueSoon, locale),
        })}
      </p>
      <button
        type="button"
        className="cortex-admin-btn request-sla-toggle"
        onClick={() => onToggle(!atRisk)}
      >
        {atRisk ? t('support.requests.sla.showAll') : t('support.requests.sla.showAtRisk')}
      </button>
    </div>
  );
}
