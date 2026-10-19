import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { iconForWorkflow } from './workflowIconKey';
import { WorkflowIcon } from './WorkflowIcon';
import { metaFor, stepsFor } from './workflowMeta';
import { PipelineThumb } from './PipelineThumb';
import './WorkflowCard.css';

interface WorkflowCardProps {
  readonly workflow: WorkflowResponse;
}

function etaLabel(seconds: number, t: TFunction): string {
  const locale = activeLocale();
  if (seconds < 60) return t('dashboard.card.etaSeconds', { value: formatNumber(seconds, locale) });
  const minutes = Math.round(seconds / 30) / 2;
  return t('dashboard.card.etaMinutes', { value: formatNumber(minutes, locale) });
}

const TONE_TO_CLASS: Record<'ready' | 'experimental' | 'attention', string> = {
  ready: 'workflow-card__status--ready',
  experimental: 'workflow-card__status--experimental',
  attention: 'workflow-card__status--attention',
};

export function WorkflowCard({ workflow }: Readonly<WorkflowCardProps>) {
  const { t } = useTranslation();
  const meta = metaFor(workflow, t);
  const steps = stepsFor(workflow, t);
  const title = workflow.name ?? t('dashboard.card.untitled');
  const linkTo = workflow.id ? `/workflows/${workflow.id}` : '/workflows';
  const iconKey = iconForWorkflow(workflow.name);
  const stepCount = steps.length;

  return (
    <article className="workflow-card" tabIndex={-1}>
      <Link to={linkTo} className="workflow-card__link" aria-label={workflow.name ? t('dashboard.card.open', { name: workflow.name }) : t('dashboard.card.openUnnamed')}>
        <header className="workflow-card__head">
          <span className="workflow-card__icon" aria-hidden="true">
            <WorkflowIcon icon={iconKey} size={20} />
          </span>
          <div className="workflow-card__head-text">
            <h3 className="workflow-card__title">{title}</h3>
            {workflow.isShared && <p className="workflow-card__sub">{t('dashboard.card.sharedWithAll')}</p>}
          </div>
          <span className={`workflow-card__status ${TONE_TO_CLASS[meta.statusTone]}`}>
            {meta.statusLabel}
          </span>
        </header>

        <PipelineThumb steps={steps} />

        <p className="workflow-card__description">{meta.description}</p>

        <footer className="workflow-card__foot">
          <span className="workflow-card__meta">{etaLabel(meta.etaSeconds, t)}</span>
          <span className="workflow-card__meta-sep" aria-hidden="true" />
          <span className="workflow-card__meta">{t('dashboard.card.steps', { n: stepCount })}</span>
          <span className="workflow-card__cta" aria-hidden="true">
            {t('dashboard.card.launch')}
            <svg viewBox="0 0 24 24" width="14" height="14" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" role="presentation">
              <path d="M5 12h14M13 6l6 6-6 6" />
            </svg>
          </span>
        </footer>
      </Link>
    </article>
  );
}
