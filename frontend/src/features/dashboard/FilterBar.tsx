import { useTranslation } from 'react-i18next';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import type { WorkflowMeta } from './workflowMeta';
import './FilterBar.css';

export type CategoryFilter = 'all' | WorkflowMeta['category'];

interface FilterBarProps {
  readonly value: CategoryFilter;
  readonly onChange: (next: CategoryFilter) => void;
  readonly counts: Readonly<Record<CategoryFilter, number>>;
}

const ORDER: readonly CategoryFilter[] = ['all', 'video', 'audio', 'demo', 'production'];
const LABEL_KEYS: Record<CategoryFilter, string> = {
  all: 'dashboard.filter.all',
  video: 'dashboard.filter.video',
  audio: 'dashboard.filter.audio',
  demo: 'dashboard.filter.demo',
  production: 'dashboard.filter.production',
};

export function FilterBar({ value, onChange, counts }: Readonly<FilterBarProps>) {
  const { t } = useTranslation();
  const locale = activeLocale();
  return (
    <fieldset className="dashboard-filter">
      <legend className="visually-hidden">{t('dashboard.filter.legend')}</legend>
      {ORDER.map((key) => {
        const isActive = value === key;
        const count = counts[key] ?? 0;
        const isEmpty = key !== 'all' && count === 0;
        return (
          <button
            key={key}
            type="button"
            aria-pressed={isActive}
            disabled={isEmpty}
            className={`dashboard-filter__chip${isActive ? ' is-active' : ''}${isEmpty ? ' is-empty' : ''}`}
            onClick={() => onChange(key)}
          >
            <span>{t(LABEL_KEYS[key])}</span>
            <span className="dashboard-filter__count" aria-hidden="true">
              {formatNumber(count, locale)}
            </span>
            <span className="visually-hidden">
              {t('dashboard.filter.count', { n: formatNumber(count, locale) })}
            </span>
          </button>
        );
      })}
    </fieldset>
  );
}
