import { ArrowRight, FileText } from '@phosphor-icons/react';
import { useTranslation } from 'react-i18next';
import { Link } from 'react-router-dom';
import { formatNumber } from '../../lib/format';
import { activeLocale } from '../../lib/i18n';
import type { CategoryFilter } from './FilterBar';
import { PipelineThumb } from './PipelineThumb';
import type { StepView } from './workflowMeta';
import './WorkflowCard.css';

const SEARCH_TERMS = [
  'cv',
  'cv checker',
  'kiểm tra cv',
  'resume',
  'pdf',
  'ats',
  'star',
  'hồ sơ',
  'việc làm',
  'jobs',
];

export function matchesCvCheckCard(query: string, category: CategoryFilter): boolean {
  if (category !== 'all' && category !== 'production') return false;
  const needle = query.trim().toLowerCase();
  if (needle === '') return true;
  return SEARCH_TERMS.some((term) => term.includes(needle));
}

export function CvCheckCard() {
  const { t } = useTranslation();
  const locale = activeLocale();
  const title = t('cvCheck.homeCard.title', 'Kiểm tra CV');
  const steps: readonly StepView[] = [
    { id: 'cv-read', label: t('cvCheck.homeCard.steps.readPdf', 'Đọc PDF'), kind: 'normal' },
    { id: 'cv-ats', label: t('cvCheck.homeCard.steps.checkAts', 'Kiểm tra ATS'), kind: 'normal' },
    { id: 'cv-score', label: t('cvCheck.homeCard.steps.scoreCv', 'Chấm điểm CV'), kind: 'normal' },
    { id: 'cv-jobs', label: t('cvCheck.homeCard.steps.findJobs', 'Tìm việc'), kind: 'normal' },
  ];

  return (
    <article className="workflow-card" tabIndex={-1}>
      <Link
        to="/check-cv"
        className="workflow-card__link"
        aria-label={t('dashboard.card.open', { name: title })}
      >
        <div className="workflow-card__media" data-tone="cyan">
          <span className="workflow-card__icon" aria-hidden="true">
            <FileText size={20} />
          </span>
          <PipelineThumb steps={steps} />
        </div>

        <div className="workflow-card__body">
          <h3 className="workflow-card__title">{title}</h3>
          <p className="workflow-card__description">
            {t(
              'cvCheck.homeCard.description',
              'Tải lên file CV PDF để chấm điểm chuẩn quốc tế ATS, tối ưu câu STAR và gợi ý việc làm phù hợp.',
            )}
          </p>
          <ul className="workflow-card__chips">
            <li className="workflow-card__chip workflow-card__status workflow-card__status--ready">
              {t('dashboard.status.ready')}
            </li>
            <li className="workflow-card__chip">{t('dashboard.card.sharedWithAll')}</li>
            <li className="workflow-card__chip">
              {t('dashboard.card.etaSeconds', { value: formatNumber(10, locale) })}
            </li>
            <li className="workflow-card__chip">
              {t('dashboard.card.steps', { count: steps.length, n: steps.length })}
            </li>
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
