import { ArrowRight } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { problemDetailOf } from '../lib/problemMessage';
import { HomePrompt } from '../features/chat/HomePrompt';
import { useFeatures } from './useFeatures';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import { FilterBar } from '../features/dashboard/FilterBar';
import { SearchBar } from '../features/dashboard/SearchBar';
import { useDashboardWorkflows } from '../features/dashboard/useDashboardWorkflows';
import { WorkflowCard } from '../features/dashboard/WorkflowCard';
import { PageHeading } from './PageHeading';
import { Skeleton } from './Skeleton';
import { StatusBlock } from './StatusBlock';
import './HomePage.css';

const STAGES = [
  { order: 1, title: 'common.home.stages.source.title', body: 'common.home.stages.source.body' },
  { order: 2, title: 'common.home.stages.process.title', body: 'common.home.stages.process.body' },
  { order: 3, title: 'common.home.stages.review.title', body: 'common.home.stages.review.body' },
  { order: 4, title: 'common.home.stages.export.title', body: 'common.home.stages.export.body' },
] as const;

const PLACEHOLDER_CARDS = 4;

export function HomePage() {
  const { t } = useTranslation();
  const { chat } = useFeatures();
  const meQuery = useMeQuery();
  const workflowsQuery = useWorkflowsQuery();
  const workflows = workflowsQuery.data ?? [];
  const dashboard = useDashboardWorkflows(workflows);
  const { filtered, counts, query, setQuery, category, setCategory, reset } = dashboard;
  const hasWorkflows = workflows.length > 0;
  const workflowsReady = !workflowsQuery.isLoading && !workflowsQuery.isError;

  if (meQuery.isLoading)
    return <output className="dashboard-loading">{t('common.home.loading')}</output>;
  if (meQuery.isError) {
    return (
      <div className="launchpad">
        <StatusBlock
          kind="error"
          message={t('common.home.loadProfileError')}
          action={{ label: t('common.state.retry'), onClick: () => void meQuery.refetch() }}
        />
      </div>
    );
  }
  if (!meQuery.data) {
    return <output className="dashboard-loading">{t('common.home.loading')}</output>;
  }

  const name = meQuery.data.name?.trim();
  const greeting = name ? t('common.home.greeting', { name }) : t('common.home.greetingNoName');

  return (
    <div className="launchpad">
      <PageHeading
        title={greeting}
        lede={t('common.home.lede')}
        actions={
          <Link to="/workflows" className="cortex-btn cortex-btn--primary">
            {t('common.home.start')}
          </Link>
        }
      />
      {chat && <HomePrompt />}

      <section className="launchpad-cards" aria-labelledby="launchpad-cards-title">
        <header className="launchpad-cards__head">
          <h2 id="launchpad-cards-title" className="launchpad-section-title">
            {t('common.home.pickTitle')}
          </h2>
          {hasWorkflows && (
            <Link to="/workflows" className="launchpad-cards__see-all">
              {t('common.home.viewAll')}
              <ArrowRight size={14} weight="bold" aria-hidden="true" />
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

        {workflowsQuery.isError && (
          <StatusBlock
            kind="error"
            message={problemDetailOf(workflowsQuery.error) ?? t('common.home.loadWorkflowsError')}
            action={{
              label: t('common.state.retry'),
              onClick: () => void workflowsQuery.refetch(),
            }}
          />
        )}
        {workflowsReady && hasWorkflows && filtered.length === 0 && (
          <StatusBlock
            kind="empty"
            message={t('common.state.noMatch')}
            action={{ label: t('common.state.clearFilters'), onClick: reset }}
          />
        )}
        {workflowsReady && !hasWorkflows && (
          <StatusBlock
            kind="empty"
            message={t('common.state.empty')}
            action={{ label: t('common.home.readGuide'), to: '/docs/guides' }}
          />
        )}

        {(workflowsQuery.isLoading || filtered.length > 0) && (
          <div className="workflow-grid-region">
            <div className="workflow-grid" aria-busy={workflowsQuery.isLoading || undefined}>
              {workflowsQuery.isLoading && <Skeleton shape="card" count={PLACEHOLDER_CARDS} />}
              {filtered.map((workflow) => (
                <WorkflowCard key={workflow.id ?? workflow.name} workflow={workflow} />
              ))}
            </div>
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
    </div>
  );
}
