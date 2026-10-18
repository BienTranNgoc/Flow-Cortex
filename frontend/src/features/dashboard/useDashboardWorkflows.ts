import { useMemo, useState } from 'react';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import type { CategoryFilter } from './FilterBar';
import { metaFor } from './workflowMeta';

export interface DashboardWorkflows {
  readonly filtered: readonly WorkflowResponse[];
  readonly counts: Readonly<Record<CategoryFilter, number>>;
  readonly query: string;
  readonly setQuery: (next: string) => void;
  readonly category: CategoryFilter;
  readonly setCategory: (next: CategoryFilter) => void;
  readonly reset: () => void;
}

function matchesQuery(workflow: WorkflowResponse, query: string): boolean {
  if (query === '') return true;
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  if ((workflow.name ?? '').toLowerCase().includes(needle)) return true;
  return (workflow.steps ?? []).some((step) =>
    (step.name ?? '').toLowerCase().includes(needle),
  );
}

function matchesCategory(workflow: WorkflowResponse, category: CategoryFilter): boolean {
  if (category === 'all') return true;
  return metaFor(workflow).category === category;
}

function emptyCounts(): Record<CategoryFilter, number> {
  return { all: 0, video: 0, audio: 0, demo: 0, production: 0 };
}

export function useDashboardWorkflows(workflows: readonly WorkflowResponse[]): DashboardWorkflows {
  const [query, setQuery] = useState('');
  const [category, setCategory] = useState<CategoryFilter>('all');

  const counts = useMemo<Record<CategoryFilter, number>>(() => {
    const next = emptyCounts();
    for (const workflow of workflows) {
      next.all += 1;
      const meta = metaFor(workflow);
      next[meta.category] += 1;
    }
    return next;
  }, [workflows]);

  const filtered = useMemo<readonly WorkflowResponse[]>(() => {
    return workflows.filter(
      (workflow) => matchesQuery(workflow, query) && matchesCategory(workflow, category),
    );
  }, [workflows, query, category]);

  return {
    filtered,
    counts,
    query,
    setQuery,
    category,
    setCategory,
    reset: () => {
      setQuery('');
      setCategory('all');
    },
  };
}
