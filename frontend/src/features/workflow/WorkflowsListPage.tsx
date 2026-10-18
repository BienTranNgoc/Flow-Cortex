import type { TFunction } from 'i18next';
import { useMemo, useState } from 'react';
import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { useMeQuery } from '../auth/useAuth';
import { iconForWorkflow } from '../dashboard/workflowIconKey';
import { metaFor, type StatusTone } from '../dashboard/workflowMeta';
import { WorkflowIcon } from '../dashboard/WorkflowIcon';
import { useCapabilitiesQuery } from './useCapabilities';
import { useWorkflowsQuery } from './useWorkflows';
import { OwnerCell } from './OwnerCell';
import './WorkflowsListPage.css';

type PillTone = 'active' | 'failed' | 'attention';

interface RowStatus {
  readonly label: string;
  readonly tone: PillTone;
}

const PILL_TONE: Record<StatusTone, PillTone> = {
  ready: 'active',
  experimental: 'attention',
  attention: 'failed',
};

type ScopeFilter = 'all' | 'shared' | 'mine';
type LayoutMode = 'grid' | 'list';

const SCOPES: ReadonlyArray<{ id: ScopeFilter; labelKey: string }> = [
  { id: 'all', labelKey: 'workflow.list.scope.all' },
  { id: 'shared', labelKey: 'workflow.list.scope.shared' },
  { id: 'mine', labelKey: 'workflow.list.scope.mine' },
];

function statusFromMeta(workflow: WorkflowResponse, t: TFunction): RowStatus {
  const { statusLabel, statusTone } = metaFor(workflow, t);
  return { label: statusLabel, tone: PILL_TONE[statusTone] };
}

function safeName(workflow: WorkflowResponse, t: TFunction): string {
  return workflow.name ?? t('workflow.list.untitled');
}

function stepCount(count: number, t: TFunction): string {
  return t(count === 1 ? 'workflow.list.stepOne' : 'workflow.list.stepOther', {
    n: formatNumber(count, activeLocale()),
  });
}

/** `/workflows` — search and scope the visible workflows, then open one to run it. */
export function WorkflowsListPage() {
  const { t } = useTranslation();
  const workflowsQuery = useWorkflowsQuery();
  const meQuery = useMeQuery();
  const capabilitiesQuery = useCapabilitiesQuery();
  const capabilities = capabilitiesQuery.data;

  const [query, setQuery] = useState('');
  const [scope, setScope] = useState<ScopeFilter>('all');
  const [layout, setLayout] = useState<LayoutMode>('list');

  const visible = useMemo(() => {
    if (capabilitiesQuery.isPending) return undefined;
    return (workflowsQuery.data ?? []).filter(
      (workflow) =>
        capabilities?.productVideoEnabled !== false ||
        workflow.id !== capabilities.productVideoWorkflowId,
    );
  }, [capabilitiesQuery.isPending, workflowsQuery.data, capabilities]);

  const filtered = useMemo(() => {
    if (!visible) return undefined;
    const q = query.trim().toLowerCase();
    return visible.filter((workflow) => {
      if (scope === 'shared' && !workflow.isShared) return false;
      if (scope === 'mine' && workflow.isShared) return false;
      if (q === '') return true;
      return safeName(workflow, t).toLowerCase().includes(q);
    });
  }, [visible, query, scope, t]);

  const total = visible?.length ?? 0;
  const showLoading =
    workflowsQuery.isLoading || (capabilitiesQuery.isPending && !workflowsQuery.isError);

  const firstName = meQuery.data?.name?.split(' ')[0] ?? t('workflow.list.nameFallback');
  const locale = activeLocale();

  return (
    <main className="wf-list">
      <header className="wf-list__header">
        <div>
          <h1 className="wf-list__title">{t('workflow.list.heading')}</h1>
          <p className="wf-list__welcome">
            <Trans
              i18nKey="workflow.list.welcomeBack"
              values={{ name: firstName }}
              components={{ strong: <strong /> }}
            />
          </p>
        </div>
      </header>

      <div className="wf-list__toolbar">
        <label className="wf-list__search" aria-label={t('workflow.list.searchLabel')}>
          <svg
            viewBox="0 0 24 24"
            width="16"
            height="16"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.8"
            strokeLinecap="round"
            strokeLinejoin="round"
            aria-hidden="true"
          >
            <circle cx="11" cy="11" r="7" />
            <path d="M20 20l-3.5-3.5" />
          </svg>
          <input
            type="search"
            placeholder={t('workflow.list.searchPlaceholder')}
            value={query}
            onChange={(event) => setQuery(event.target.value)}
          />
        </label>

        <fieldset className="wf-list__scope">
          <legend className="visually-hidden">{t('workflow.list.scope.legend')}</legend>
          <span aria-hidden="true">{t('workflow.list.scope.prefix')}</span>
          {SCOPES.map((s) => (
            <button
              key={s.id}
              type="button"
              aria-pressed={scope === s.id}
              className={`wf-list__scope-btn${scope === s.id ? ' is-active' : ''}`}
              onClick={() => setScope(s.id)}
            >
              {t(s.labelKey)}
            </button>
          ))}
        </fieldset>

        <fieldset className="wf-list__layout">
          <legend className="visually-hidden">{t('workflow.list.layout.legend')}</legend>
          <button
            type="button"
            aria-pressed={layout === 'grid'}
            className={`wf-list__layout-btn${layout === 'grid' ? ' is-active' : ''}`}
            onClick={() => setLayout('grid')}
            aria-label={t('workflow.list.layout.grid')}
          >
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <rect x="3" y="3" width="7" height="7" />
              <rect x="14" y="3" width="7" height="7" />
              <rect x="3" y="14" width="7" height="7" />
              <rect x="14" y="14" width="7" height="7" />
            </svg>
          </button>
          <button
            type="button"
            aria-pressed={layout === 'list'}
            className={`wf-list__layout-btn${layout === 'list' ? ' is-active' : ''}`}
            onClick={() => setLayout('list')}
            aria-label={t('workflow.list.layout.list')}
          >
            <svg
              viewBox="0 0 24 24"
              width="16"
              height="16"
              fill="none"
              stroke="currentColor"
              strokeWidth="1.6"
              strokeLinecap="round"
              strokeLinejoin="round"
              aria-hidden="true"
            >
              <line x1="3" y1="6" x2="21" y2="6" />
              <line x1="3" y1="12" x2="21" y2="12" />
              <line x1="3" y1="18" x2="21" y2="18" />
            </svg>
          </button>
        </fieldset>
      </div>

      {showLoading && <output className="wf-list__status">{t('workflow.list.loading')}</output>}
      {workflowsQuery.isError && !showLoading && (
        <p role="alert" className="wf-list__status wf-list__status--error">
          {t('workflow.list.loadError')}
        </p>
      )}
      {filtered && filtered.length === 0 && !showLoading && (
        <p className="wf-list__status">{t('workflow.list.noMatch')}</p>
      )}

      {filtered && filtered.length > 0 && (
        <>
          {layout === 'list' ? (
            <div className="wf-list__table-wrap" role="region" aria-label={t('workflow.list.tableLabel')}>
              <table className="wf-list__table">
                <thead>
                  <tr>
                    <th scope="col">{t('workflow.list.column.name')}</th>
                    <th scope="col">{t('workflow.list.column.status')}</th>
                    <th scope="col">{t('workflow.list.column.owner')}</th>
                    <th scope="col">{t('workflow.list.column.steps')}</th>
                    <th scope="col" className="wf-list__actions-col">{t('workflow.list.column.actions')}</th>
                  </tr>
                </thead>
                <tbody>
                  {filtered.map((workflow) => {
                    const status = statusFromMeta(workflow, t);
                    const steps = workflow.steps ?? [];
                    const label = safeName(workflow, t);
                    return (
                      <tr key={workflow.id}>
                        <td>
                          <div className="wf-list__name">
                            <span className="wf-list__icon" aria-hidden="true">
                              <WorkflowIcon icon={iconForWorkflow(label)} size={18} />
                            </span>
                            <div>
                              <Link to={`/workflows/${workflow.id}`} className="wf-list__name-link">
                                {label}
                              </Link>
                            </div>
                          </div>
                        </td>
                        <td>
                          <span className={`wf-list__status-pill wf-list__status-pill--${status.tone}`}>
                            <span className="wf-list__status-dot" aria-hidden="true" />
                            {status.label}
                          </span>
                        </td>
                        <td>
                          <OwnerCell workflow={workflow} />
                        </td>
                        <td className="wf-list__muted">{stepCount(steps.length, t)}</td>
                        <td>
                          <div className="wf-list__actions" aria-label={t('workflow.list.rowActions')}>
                            <Link
                              to={`/workflows/${workflow.id}`}
                              className="cortex-btn cortex-btn--icon wf-list__run-btn"
                              title={t('workflow.list.runTitle')}
                              aria-label={t('workflow.list.runNamed', { name: label })}
                            >
                              <svg
                                viewBox="0 0 24 24"
                                width="14"
                                height="14"
                                fill="currentColor"
                                aria-hidden="true"
                              >
                                <path d="M8 5v14l11-7z" />
                              </svg>
                            </Link>
                          </div>
                        </td>
                      </tr>
                    );
                  })}
                </tbody>
              </table>
            </div>
          ) : (
            <ul className="wf-list__grid">
              {filtered.map((workflow) => {
                const status = statusFromMeta(workflow, t);
                const steps = workflow.steps ?? [];
                const label = safeName(workflow, t);
                return (
                  <li key={workflow.id} className="wf-list__grid-card">
                    <div className="wf-list__name">
                      <span className="wf-list__icon" aria-hidden="true">
                        <WorkflowIcon icon={iconForWorkflow(label)} size={18} />
                      </span>
                      <Link to={`/workflows/${workflow.id}`} className="wf-list__name-link">
                        {label}
                      </Link>
                    </div>
                    <p className={`wf-list__status-pill wf-list__status-pill--${status.tone}`}>
                      <span className="wf-list__status-dot" aria-hidden="true" />
                      {status.label}
                    </p>
                    <OwnerCell workflow={workflow} />
                    <dl className="wf-list__grid-meta">
                      <div>
                        <dt>{t('workflow.list.column.steps')}</dt>
                        <dd>{stepCount(steps.length, t)}</dd>
                      </div>
                    </dl>
                    <div className="wf-list__actions">
                      <Link
                        to={`/workflows/${workflow.id}`}
                        className="cortex-btn cortex-btn--icon wf-list__run-btn"
                        title={t('workflow.list.runTitle')}
                        aria-label={t('workflow.list.runNamed', { name: label })}
                      >
                        <svg viewBox="0 0 24 24" width="14" height="14" fill="currentColor" aria-hidden="true">
                          <path d="M8 5v14l11-7z" />
                        </svg>
                      </Link>
                    </div>
                  </li>
                );
              })}
            </ul>
          )}

          <footer className="wf-list__footer">
            <span>
              {t('workflow.list.showing', {
                shown: formatNumber(filtered.length, locale),
                total: formatNumber(total, locale),
              })}
            </span>
          </footer>
        </>
      )}
    </main>
  );
}
