import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import { FilterBar } from '../features/dashboard/FilterBar';
import { SearchBar } from '../features/dashboard/SearchBar';
import { useDashboardWorkflows } from '../features/dashboard/useDashboardWorkflows';
import { WorkflowCard } from '../features/dashboard/WorkflowCard';
import './HomePage.css';

const STAGES = [
  { order: 1, title: 'common.home.stages.source.title', body: 'common.home.stages.source.body' },
  { order: 2, title: 'common.home.stages.process.title', body: 'common.home.stages.process.body' },
  { order: 3, title: 'common.home.stages.review.title', body: 'common.home.stages.review.body' },
  { order: 4, title: 'common.home.stages.export.title', body: 'common.home.stages.export.body' },
] as const;

export function HomePage() {
  const { t } = useTranslation();
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  const workflows = workflowsQuery.data ?? [];
  const dashboard = useDashboardWorkflows(workflows);
  const { filtered, counts, query, setQuery, category, setCategory, reset } = dashboard;
  const hasWorkflows = workflows.length > 0;

  if (meQuery.isLoading) return <output className="dashboard-loading">{t('common.home.loading')}</output>;
  if (meQuery.isError) {
    return (
      <main className="launchpad">
        <p role="alert" className="form-error">
          {t('common.home.loadProfileError')}{' '}
          <button type="button" onClick={() => void meQuery.refetch()}>
            {t('common.home.retry')}
          </button>
        </p>
      </main>
    );
  }
  if (!meQuery.data) {
    return <output className="dashboard-loading">{t('common.home.loading')}</output>;
  }

  return (
    <main className="launchpad">
      <section className="launchpad-hero" aria-labelledby="launchpad-hero-title">
        <div className="launchpad-hero__glow" aria-hidden="true">
          <span className="launchpad-hero__glow-a" />
          <span className="launchpad-hero__glow-b" />
        </div>
        <div className="launchpad-hero__badge">
          <span className="launchpad-hero__pulse" aria-hidden="true" />
          <span className="launchpad-hero__bolt" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="12" height="12" fill="currentColor" aria-hidden="true">
              <path d="M13 2L4 14h6l-1 8 9-12h-6z" />
            </svg>
          </span>
          <span>{t('common.home.eyebrow')}</span>
        </div>
        <h1 id="launchpad-hero-title" className="launchpad-hero__title">
          <Trans
            i18nKey="common.home.headline"
            components={{
              strong: <span className="launchpad-hero__title-strong" />,
              accent: <span className="launchpad-hero__title-accent" />,
            }}
          />
        </h1>
        <p className="launchpad-hero__lede">{t('common.home.lede')}</p>
      </section>

      <section className="launchpad-cards" aria-labelledby="launchpad-cards-title">
        <header className="launchpad-cards__head">
          <h2 id="launchpad-cards-title" className="launchpad-section-title">
            {t('common.home.pickTitle')}
          </h2>
          {hasWorkflows && (
            <Link to="/workflows" className="launchpad-cards__see-all">
              {t('common.home.viewAll')}
              <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M5 12h14M13 6l6 6-6 6" />
              </svg>
            </Link>
          )}
        </header>

        {hasWorkflows && (
          <div className="launchpad-cards__controls">
            <div className="launchpad-cards__search">
              <SearchBar value={query} onChange={setQuery} />
            </div>
            <FilterBar value={category} onChange={setCategory} counts={counts} />
          </div>
        )}

        {workflowsQuery.isLoading && <output>{t('common.home.loadingWorkflows')}</output>}
        {workflowsQuery.isError && (
          <p role="alert" className="form-error">
            {t('common.home.loadWorkflowsError')}{' '}
            <button type="button" onClick={() => void workflowsQuery.refetch()}>
              {t('common.home.retry')}
            </button>
          </p>
        )}
        {hasWorkflows && !workflowsQuery.isLoading && !workflowsQuery.isError && filtered.length === 0 && (
          <div className="launchpad-cards__empty">
            <p role="status">{t('common.home.noMatch')}</p>
            <button type="button" onClick={reset}>
              {t('common.home.clearFilters')}
            </button>
          </div>
        )}
        {!hasWorkflows && !workflowsQuery.isLoading && !workflowsQuery.isError && (
          <p>{t('common.home.empty')}</p>
        )}

        {filtered.length > 0 && (
          <div className="workflow-grid">
            {filtered.map((workflow) => (
              <WorkflowCard key={workflow.id ?? workflow.name} workflow={workflow} />
            ))}
          </div>
        )}
      </section>

      <section className="launchpad-stages" aria-labelledby="launchpad-stages-title">
        <h2 id="launchpad-stages-title">{t('common.home.stagesTitle')}</h2>
        <ol className="launchpad-stages-list">
          {STAGES.map(({ order, title, body }) => (
            <li key={order} className="launchpad-stage">
              <span className="launchpad-stage-order" aria-hidden="true">
                {order}
              </span>
              <h3>{t(title)}</h3>
              <p>{t(body)}</p>
            </li>
          ))}
        </ol>
      </section>

      <section className="launchpad-footer">
        <p>
          {t('common.home.welcome', { name: meQuery.data.name })} ·{' '}
          <Link to="/profile">{t('common.home.manageProfile')}</Link>
        </p>
      </section>
    </main>
  );
}
