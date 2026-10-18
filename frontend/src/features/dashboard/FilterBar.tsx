import type { WorkflowMeta } from './workflowMeta';
import './FilterBar.css';

export type CategoryFilter = 'all' | WorkflowMeta['category'];

interface FilterBarProps {
  readonly value: CategoryFilter;
  readonly onChange: (next: CategoryFilter) => void;
  readonly counts: Readonly<Record<CategoryFilter, number>>;
}

const ORDER: readonly CategoryFilter[] = ['all', 'video', 'audio', 'demo', 'production'];
const LABELS: Record<CategoryFilter, string> = {
  all: 'All',
  video: 'Video',
  audio: 'Audio',
  demo: 'Demos',
  production: 'Production',
};

export function FilterBar({ value, onChange, counts }: Readonly<FilterBarProps>) {
  return (
    <fieldset className="dashboard-filter">
      <legend className="visually-hidden">Filter workflows by category</legend>
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
            <span>{LABELS[key]}</span>
            <span className="dashboard-filter__count" aria-hidden="true">
              {count}
            </span>
            <span className="visually-hidden">{count} workflows</span>
          </button>
        );
      })}
    </fieldset>
  );
}
