import { ArrowRight } from '@phosphor-icons/react';
import type { TFunction } from 'i18next';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import type { WorkflowResponse } from '@flow-cortex/api-client';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import { iconForWorkflow } from './workflowIconKey';
import { WorkflowIcon } from './WorkflowIcon';
import { metaFor, stepsFor, type WorkflowMeta } from './workflowMeta';
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

const CATEGORY_TONE: Record<WorkflowMeta['category'], string> = {
  video: 'blue',
  audio: 'violet',
  demo: 'amber',
  production: 'cyan',
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
        <div className="workflow-card__media" data-tone={CATEGORY_TONE[meta.category]}>
          <span className="workflow-card__icon" aria-hidden="true">
            <WorkflowIcon icon={iconKey} size={20} />
          </span>
          <PipelineThumb steps={steps} />
        </div>

        <div className="workflow-card__body">
          <h3 className="workflow-card__title">{title}</h3>
          <p className="workflow-card__description">{meta.description}</p>
          <ul className="workflow-card__chips">
            <li className={`workflow-card__chip workflow-card__status ${TONE_TO_CLASS[meta.statusTone]}`}>
              {meta.statusLabel}
            </li>
            {workflow.isShared && (
              <li className="workflow-card__chip">{t('dashboard.card.sharedWithAll')}</li>
            )}
            <li className="workflow-card__chip">{etaLabel(meta.etaSeconds, t)}</li>
            <li className="workflow-card__chip">{t('dashboard.card.steps', { count: stepCount, n: stepCount })}</li>
          </ul>
        </div>

        <span className="workflow-card__cta" aria-hidden="true">
          {t('dashboard.card.launch')}
          <ArrowRight size={14} weight="bold" />
        </span>
      </Link>
    </article>
  );
}
