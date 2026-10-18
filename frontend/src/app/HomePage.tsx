import { type WorkflowResponse, type WorkflowStepResponseMode } from '@flow-cortex/api-client';
import { Trans, useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { useMeQuery } from '../features/auth/useAuth';
import { useWorkflowsQuery } from '../features/workflow/useWorkflows';
import './HomePage.css';

type StageTone = 'blue' | 'cyan' | 'violet' | 'amber' | 'green' | 'rose';
type IconTone = 'blue' | 'cyan' | 'amber';

const TONE_BY_MODE: Record<WorkflowStepResponseMode, IconTone> = {
  inline: 'blue',
  remote: 'cyan',
};
const DEFAULT_TONE: IconTone = 'amber';
const WORKFLOWS_PATH = '/workflows';

const STAGE_TONE_CYCLE: readonly StageTone[] = ['blue', 'cyan', 'violet', 'amber', 'green', 'rose'];

function firstStepMode(workflow: WorkflowResponse): WorkflowStepResponseMode | undefined {
  const first = workflow.steps?.[0];
  return first?.mode;
}

function iconFor(workflow: WorkflowResponse): string {
  return (workflow.name ?? workflow.id ?? '?').slice(0, 2).toUpperCase();
}

function stageToneAt(index: number): StageTone {
  return STAGE_TONE_CYCLE[index % STAGE_TONE_CYCLE.length] as StageTone;
}

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
  if (meQuery.isLoading) return <output>{t('common.home.loading')}</output>;
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
    return <output>{t('common.home.loading')}</output>;
  }

  const workflows = workflowsQuery.data ?? [];

  return (
    <main className="launchpad">
      <section className="launchpad-hero">
        <p className="eyebrow">{t('common.home.eyebrow')}</p>
        <h1>
          <Trans i18nKey="common.home.headline" components={{ highlight: <span /> }} />
        </h1>
        <p>{t('common.home.lede')}</p>
      </section>

      <section className="launchpad-cards" aria-labelledby="launchpad-cards-title">
        <h2 id="launchpad-cards-title" className="launchpad-section-title">
          {t('common.home.pickTitle')}
        </h2>
        {workflowsQuery.isLoading && <output>{t('common.home.loadingWorkflows')}</output>}
        {workflowsQuery.isError && (
          <p role="alert" className="form-error">
            {t('common.home.loadWorkflowsError')}{' '}
            <button type="button" onClick={() => void workflowsQuery.refetch()}>
              {t('common.home.retry')}
            </button>
          </p>
        )}
        {!workflowsQuery.isLoading && !workflowsQuery.isError && workflows.length === 0 && (
          <p>{t('common.home.empty')}</p>
        )}
        {workflows.length > 0 && (
          <div className="workflow-grid">
            {workflows.map((workflow) => {
              const mode = firstStepMode(workflow);
              const iconTone: IconTone = mode ? TONE_BY_MODE[mode] : DEFAULT_TONE;
              const linkTo = workflow.id ? `${WORKFLOWS_PATH}/${workflow.id}` : WORKFLOWS_PATH;
              return (
                <article key={workflow.id ?? workflow.name} className="workflow-card-tile">
                  <header className="workflow-card-header">
                    <span className={`workflow-card-icon tone-${iconTone}`} aria-hidden="true">
                      {iconFor(workflow)}
                    </span>
                    <h3 className="workflow-card-title">
                      {workflow.name ?? t('common.home.untitled')}
                    </h3>
                  </header>

                  {workflow.steps && workflow.steps.length > 0 && (
                    <ul className="workflow-card-stages">
                      {workflow.steps.map((step, index) => (
                        <li
                          key={step.id ?? `${index}-${step.name}`}
                          className={`workflow-card-stage tone-${stageToneAt(index)}`}
                        >
                          {step.name ??
                            t('common.home.stepFallback', { order: step.stepOrder ?? index + 1 })}
                        </li>
                      ))}
                    </ul>
                  )}

                  <footer className="workflow-card-footer">
                    <Link className="workflow-card-launch" to={linkTo}>
                      {t('common.home.launch')}
                    </Link>
                  </footer>
                </article>
              );
            })}
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
          <Link to="/workflows">{t('common.home.viewAll')}</Link>
        </p>
      </section>
    </main>
  );
}
