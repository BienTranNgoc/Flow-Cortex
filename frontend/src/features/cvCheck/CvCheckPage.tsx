import {
  ArrowSquareOut,
  ArrowsClockwise,
  Briefcase,
  Buildings,
  CheckCircle,
  FilePdf,
  FileText,
  MapPin,
  Sparkle,
  Trash,
  UploadSimple,
  WarningCircle,
} from '@phosphor-icons/react';
import type { TFunction } from 'i18next';
import { type ChangeEvent, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import {
  type CvCheckReport,
  type JobRecommendationsResponse,
} from './cvCheckTypes';
import { CvCheckError, useCvCheckMutation, useRecommendJobsMutation } from './useCvCheck';
import './CvCheckPage.css';

type ActiveTab = 'evaluation' | 'bullets' | 'interview' | 'jobs';
type ProgressMode = 'idle' | 'stepper' | 'loading';
type StepState = 'done' | 'active' | 'pending';

const MAX_STEPPER_MS = 10_000;

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function getScoreVerdict(score: number, t: TFunction) {
  if (score >= 90) {
    return {
      cls: 'score--excellent',
      label: t('cvCheck.verdict.tier1', 'Tier 1 — Xuất sắc (Chuẩn Big Tech)'),
    };
  }
  if (score >= 75) {
    return {
      cls: 'score--good',
      label: t('cvCheck.verdict.tier2', 'Tier 2 — Đạt chuẩn tuyển dụng (SHRM 75+)'),
    };
  }
  if (score >= 60) {
    return {
      cls: 'score--moderate',
      label: t('cvCheck.verdict.tier3', 'Tier 3 — Cần tối ưu trước khi nộp'),
    };
  }
  return {
    cls: 'score--poor',
    label: t('cvCheck.verdict.tier4', 'Tier 4 — Nguy cơ bị ATS loại sớm'),
  };
}

function atsStatusLabel(status: 'PASS' | 'WARN' | 'FAIL', t: TFunction): string {
  if (status === 'PASS') return t('cvCheck.atsChecklist.pass', 'ĐẠT');
  if (status === 'WARN') return t('cvCheck.atsChecklist.warn', 'CẢNH BÁO');
  return t('cvCheck.atsChecklist.fail', 'CHƯA ĐẠT');
}

function resolveStepState(index: number, currentStep: number): StepState {
  if (index < currentStep) return 'done';
  if (index === currentStep) return 'active';
  return 'pending';
}

function renderStepIcon(state: StepState) {
  if (state === 'done') return <CheckCircle size={18} weight="fill" />;
  if (state === 'active') return <ArrowsClockwise size={16} className="spinner" />;
  return <span className="cv-check-stepper__dot" />;
}

function CvErrorBanner({ error, t }: Readonly<{ error: unknown; t: TFunction }>) {
  const errCode = error instanceof CvCheckError ? error.code : undefined;
  const isNotCv = errCode === 'cv-not-a-cv';
  const extraReason = isNotCv && error instanceof CvCheckError ? error.message : '';

  let message = t(
    'cvCheck.error.general',
    'Đã xảy ra lỗi khi phân tích CV. Vui lòng kiểm tra lại file PDF hoặc thử lại sau ít phút.',
  );
  if (isNotCv) {
    message = t(
      'cvCheck.error.notACv',
      'Có vẻ file PDF này không phải là CV. Vui lòng kiểm tra lại và tải lên đúng hồ sơ xin việc của bạn.',
    );
  } else if (errCode === 'cv-file-too-large') {
    message = t(
      'cvCheck.error.fileTooLarge',
      'File PDF vượt quá dung lượng tối đa 8MB. Vui lòng nén nhỏ file hoặc chọn file khác.',
    );
  }

  return (
    <div className={isNotCv ? 'cv-check-warning-banner' : 'cv-check-error-banner'} role="alert">
      <WarningCircle size={20} weight="fill" aria-hidden="true" />
      <div className="cv-check-banner-text">
        <span>{message}</span>
        {extraReason && <span className="cv-check-banner-reason">{extraReason}</span>}
      </div>
    </div>
  );
}

function CvProgressIndicator({
  progressMode,
  progressStep,
  isPending,
  t,
}: Readonly<{
  progressMode: ProgressMode;
  progressStep: number;
  isPending: boolean;
  t: TFunction;
}>) {
  if (progressMode === 'stepper') {
    const steps = [
      { id: 'readPdf', label: t('cvCheck.progress.readPdf', 'Đọc file PDF') },
      { id: 'checkAts', label: t('cvCheck.progress.checkAts', 'Kiểm tra bố cục & ATS') },
      { id: 'scoreCv', label: t('cvCheck.progress.scoreCv', 'Đánh giá & Chấm điểm') },
      { id: 'findJobs', label: t('cvCheck.progress.findJobs', 'Tìm việc phù hợp') },
    ];
    return (
      <output className="cv-check-stepper" aria-live="polite">
        {steps.map((step, idx) => {
          const state = resolveStepState(idx, progressStep);
          return (
            <div key={step.id} className={`cv-check-stepper__item is-${state}`}>
              <span className="cv-check-stepper__icon" aria-hidden="true">
                {renderStepIcon(state)}
              </span>
              <span className="cv-check-stepper__label">{step.label}</span>
            </div>
          );
        })}
      </output>
    );
  }

  if (progressMode === 'loading' && isPending) {
    return (
      <output className="cv-check-loading-strip" aria-live="polite">
        <div className="cv-check-loading-strip__row">
          <ArrowsClockwise size={18} className="spinner" aria-hidden="true" />
          <span>{t('cvCheck.progress.finalizing', 'Đang hoàn tất phân tích...')}</span>
        </div>
        <div className="cv-check-loading-strip__track">
          <div className="cv-check-loading-strip__bar" />
        </div>
      </output>
    );
  }

  return null;
}

function CvOverviewCard({ report, t }: Readonly<{ report: CvCheckReport; t: TFunction }>) {
  const verdict = getScoreVerdict(report.overallScore, t);
  const dimensionRows = [
    {
      id: 'experienceImpact',
      label: t('cvCheck.dimensions.experienceImpact', 'Tác động & Định lượng (Google XYZ)'),
      weight: report.rubric?.weights.experienceImpact ?? 30,
      score: report.dimensions.experienceImpact,
    },
    {
      id: 'hardSkills',
      label: t('cvCheck.dimensions.hardSkills', 'Kỹ năng cốt lõi (ESCO / O*NET)'),
      weight: report.rubric?.weights.hardSkills ?? 25,
      score: report.dimensions.hardSkills,
    },
    {
      id: 'atsCompliance',
      label: t('cvCheck.dimensions.atsCompliance', 'Chuẩn ATS & Cấu trúc (SHRM)'),
      weight: report.rubric?.weights.atsCompliance ?? 20,
      score: report.dimensions.atsCompliance,
    },
    {
      id: 'domainRelevance',
      label: t('cvCheck.dimensions.domainRelevance', 'Lộ trình & Độ rõ ràng (Harvard OCS)'),
      weight: report.rubric?.weights.domainRelevance ?? 15,
      score: report.dimensions.domainRelevance,
    },
    {
      id: 'softSkills',
      label: t('cvCheck.dimensions.softSkills', 'Kỹ năng mềm & Văn phong'),
      weight: report.rubric?.weights.softSkills ?? 10,
      score: report.dimensions.softSkills,
    },
  ];

  return (
    <div className="cv-check-overview-card">
      <div className="cv-check-overview-top">
        <div className={`cv-check-score-box ${verdict.cls}`}>
          <span className="cv-check-score-number">{report.overallScore}</span>
          <span className="cv-check-score-label">
            {t('cvCheck.results.overallScore', 'Điểm chuẩn quốc tế')} / 100
          </span>
          <span className="cv-check-score-verdict">{verdict.label}</span>
          <span className="cv-check-rubric-tag">
            {report.rubric?.name ?? 'Google XYZ · Harvard OCS · SHRM ATS'}
          </span>
        </div>

        <div className="cv-check-dimension-list">
          {dimensionRows.map((dim) => (
            <div key={dim.id} className="cv-check-dim-row">
              <div className="cv-check-dim-head">
                <span>
                  {dim.label}{' '}
                  <em className="cv-check-dim-weight">({dim.weight}%)</em>
                </span>
                <span>{dim.score}%</span>
              </div>
              <div className="cv-check-dim-bar">
                <div
                  className="cv-check-dim-fill"
                  style={{ width: `${Math.min(100, Math.max(0, dim.score))}%` }}
                />
              </div>
            </div>
          ))}
        </div>
      </div>

      {Boolean(report.atsChecks?.length) && (
        <div className="cv-check-ats-section">
          <div className="cv-check-ats-card__head">
            <h3 className="cv-check-ats-card__title">
              {t('cvCheck.atsChecklist.title', 'Kiểm tra ATS & Chuẩn Quốc tế (Tất định)')}
            </h3>
            <span className="cv-check-ats-card__sub">
              {t('cvCheck.atsChecklist.subtitle', 'Đo trực tiếp từ cấu trúc PDF & nội dung CV')}
            </span>
          </div>
          <ul className="cv-check-ats-list">
            {report.atsChecks!.map((check) => (
              <li
                key={check.id}
                className={`cv-check-ats-row cv-check-ats-row--${check.status.toLowerCase()}`}
              >
                <span className="cv-check-ats-badge">{atsStatusLabel(check.status, t)}</span>
                <span className="cv-check-ats-item__detail">{check.detail}</span>
                <span className="cv-check-ats-standard">{check.standard}</span>
              </li>
            ))}
          </ul>
        </div>
      )}
    </div>
  );
}

function CvJobsTab({
  report,
  jobRecs,
  isPending,
  isError,
  activeLang,
  onRetry,
  t,
}: Readonly<{
  report: CvCheckReport;
  jobRecs: JobRecommendationsResponse | null;
  isPending: boolean;
  isError: boolean;
  activeLang: 'en' | 'vi';
  onRetry: (profile: NonNullable<CvCheckReport['jobProfile']>) => void;
  t: TFunction;
}>) {
  return (
    <div className="cv-check-tab-panel cv-check-jobs-panel">
      {report.jobProfile && (
        <div className="cv-check-job-profile-bar">
          <span className="cv-check-job-profile-label">
            {t('cvCheck.jobs.detectedProfile', 'Hồ sơ tìm việc đề xuất:')}
          </span>
          <span className="cv-check-profile-pill cv-check-profile-pill--role">
            {report.jobProfile.primaryRole}
          </span>
          {report.jobProfile.seniority && (
            <span className="cv-check-profile-pill">{report.jobProfile.seniority}</span>
          )}
          {report.jobProfile.location && (
            <span className="cv-check-profile-pill">
              <MapPin size={12} aria-hidden="true" />
              {report.jobProfile.location}
            </span>
          )}
          {jobRecs?.lastSyncedAt && (
            <span className="cv-check-profile-pill">
              {t('cvCheck.jobs.syncedNote', 'Cập nhật (7 ngày/lần):')}{' '}
              {new Date(jobRecs.lastSyncedAt).toLocaleDateString(
                activeLang === 'en' ? 'en-US' : 'vi-VN',
              )}
            </span>
          )}
          {jobRecs?.cached && (
            <span className="cv-check-profile-pill cv-check-profile-pill--role">
              {t('cvCheck.jobs.cachedBadge', 'Từ bộ nhớ đệm · Tự làm mới sau 7 ngày')}
            </span>
          )}
        </div>
      )}

      {isPending && (
        <output className="cv-check-jobs-loading">
          <ArrowsClockwise className="spinner" size={20} aria-hidden="true" />
          <span>
            {t(
              'cvCheck.jobs.loading',
              'Đang tìm tin tuyển dụng tại Việt Nam phù hợp với hồ sơ...',
            )}
          </span>
        </output>
      )}

      {isError && !isPending && (
        <div className="cv-check-jobs-error" role="alert">
          <WarningCircle size={18} weight="fill" aria-hidden="true" />
          <span>
            {t(
              'cvCheck.jobs.error',
              'Không thể tải danh sách tin tuyển dụng lúc này. Bạn có thể thử lại hoặc tìm trực tiếp trên các trang tuyển dụng.',
            )}
          </span>
          {report.jobProfile && (
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={() => onRetry(report.jobProfile!)}
            >
              {t('cvCheck.jobs.retry', 'Thử lại')}
            </button>
          )}
        </div>
      )}

      {!isPending && Boolean(jobRecs?.jobs.length) && (
        <div className="cv-check-jobs-grid">
          {jobRecs!.jobs.map((job) => (
            <article key={`${job.applyUrl}-${job.title}`} className="cv-check-job-card">
              <div className="cv-check-job-card__head">
                <div>
                  <h4 className="cv-check-job-card__title">{job.title}</h4>
                  <div className="cv-check-job-card__meta">
                    <span>
                      <Buildings size={14} aria-hidden="true" />
                      {job.company}
                    </span>
                    <span>
                      <MapPin size={14} aria-hidden="true" />
                      {job.location}
                    </span>
                    {job.workMode && <span>{job.workMode}</span>}
                  </div>
                </div>
                <span className="cv-check-job-match">
                  {job.matchScore}% {t('cvCheck.jobs.matchSuffix', 'phù hợp')}
                </span>
              </div>

              {job.salary && <div className="cv-check-job-salary">{job.salary}</div>}

              {job.summary && <p className="cv-check-job-card__summary">{job.summary}</p>}

              {job.matchedSkills.length > 0 && (
                <div className="cv-check-chips-wrap">
                  {job.matchedSkills.map((sk) => (
                    <span key={sk} className="cv-check-job-skill">
                      {sk}
                    </span>
                  ))}
                </div>
              )}

              <div className="cv-check-job-card__foot">
                <span className="cv-check-job-source">{job.source}</span>
                <a
                  href={job.applyUrl}
                  target="_blank"
                  rel="noopener noreferrer"
                  className="cortex-btn cortex-btn--ghost cv-check-job-link"
                >
                  {t('cvCheck.jobs.viewAndApply', 'Xem & Ứng tuyển')}
                  <ArrowSquareOut size={14} aria-hidden="true" />
                </a>
              </div>
            </article>
          ))}
        </div>
      )}

      {!isPending && jobRecs?.jobs.length === 0 && (
        <p className="cv-check-jobs-empty">
          {jobRecs.configured === false
            ? t(
                'cvCheck.jobs.unconfigured',
                'Chưa cấu hình khóa SERPAPI_API_KEY hoặc JSEARCH_API_KEY để tự động lấy tin tuyển dụng Việt Nam. Bạn có thể tìm trực tiếp trên các sàn tuyển dụng bên dưới.',
              )
            : t(
                'cvCheck.jobs.empty',
                'Chưa tìm thấy tin tuyển dụng trực tiếp cho vị trí này. Hãy dùng các liên kết tìm kiếm nhanh bên dưới.',
              )}
        </p>
      )}

      {Boolean(jobRecs?.searchLinks.length) && (
        <div className="cv-check-search-links">
          <span className="cv-check-search-links__label">
            {t('cvCheck.jobs.moreOnBoards', 'Tìm thêm trên các sàn tuyển dụng:')}
          </span>
          <div className="cv-check-search-links__list">
            {jobRecs!.searchLinks.map((link) => (
              <a
                key={link.url}
                href={link.url}
                target="_blank"
                rel="noopener noreferrer"
                className="cv-check-board-link"
              >
                {link.label}
                <ArrowSquareOut size={13} aria-hidden="true" />
              </a>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function CvDetailedTabs({
  report,
  activeTab,
  onSelectTab,
  jobRecs,
  jobsPending,
  jobsError,
  activeLang,
  onRetryJobs,
  t,
}: Readonly<{
  report: CvCheckReport;
  activeTab: ActiveTab;
  onSelectTab: (tab: ActiveTab) => void;
  jobRecs: JobRecommendationsResponse | null;
  jobsPending: boolean;
  jobsError: boolean;
  activeLang: 'en' | 'vi';
  onRetryJobs: (profile: NonNullable<CvCheckReport['jobProfile']>) => void;
  t: TFunction;
}>) {
  return (
    <div className="cv-check-tabs-container">
      <nav className="cv-check-tabs-nav" aria-label="Kết quả chi tiết">
        <button
          type="button"
          className={`cv-check-tab-btn ${activeTab === 'evaluation' ? 'is-active' : ''}`}
          onClick={() => onSelectTab('evaluation')}
        >
          <CheckCircle size={16} aria-hidden="true" />
          {t('cvCheck.tabs.evaluation', 'Điểm mạnh & Điểm yếu')}
        </button>
        <button
          type="button"
          className={`cv-check-tab-btn ${activeTab === 'bullets' ? 'is-active' : ''}`}
          onClick={() => onSelectTab('bullets')}
        >
          <Sparkle size={16} aria-hidden="true" />
          {t('cvCheck.tabs.bullets', 'Tối ưu câu mô tả (STAR)')}
        </button>
        <button
          type="button"
          className={`cv-check-tab-btn ${activeTab === 'interview' ? 'is-active' : ''}`}
          onClick={() => onSelectTab('interview')}
        >
          <FileText size={16} aria-hidden="true" />
          {t('cvCheck.tabs.interview', 'Dự đoán câu hỏi phỏng vấn')}
        </button>
        <button
          type="button"
          className={`cv-check-tab-btn ${activeTab === 'jobs' ? 'is-active' : ''}`}
          onClick={() => onSelectTab('jobs')}
        >
          <Briefcase size={16} aria-hidden="true" />
          {t('cvCheck.tabs.jobs', 'Việc làm phù hợp')}
          {jobsPending && <ArrowsClockwise className="spinner" size={14} aria-hidden="true" />}
          {!jobsPending && Boolean(jobRecs?.jobs.length) && (
            <span className="cv-check-tab-count">{jobRecs!.jobs.length}</span>
          )}
        </button>
      </nav>

      <div className="cv-check-tab-body">
        {activeTab === 'evaluation' && (
          <div className="cv-check-tab-panel">
            <div className="cv-check-eval-grid">
              <div className="cv-check-eval-box cv-check-eval-box--strength">
                <h4 className="cv-check-eval-box__title">
                  <CheckCircle size={18} weight="fill" aria-hidden="true" />
                  {t('cvCheck.results.strengths', 'Điểm mạnh nổi bật')}
                </h4>
                <ul className="cv-check-eval-list">
                  {report.strengths.map((str) => (
                    <li key={str}>{str}</li>
                  ))}
                </ul>
              </div>

              <div className="cv-check-eval-box cv-check-eval-box--weakness">
                <h4 className="cv-check-eval-box__title">
                  <WarningCircle size={18} weight="fill" aria-hidden="true" />
                  {t('cvCheck.results.weaknesses', 'Điểm cần cải thiện')}
                </h4>
                <ul className="cv-check-eval-list">
                  {report.weaknesses.map((weak) => (
                    <li key={weak}>{weak}</li>
                  ))}
                </ul>
              </div>
            </div>

            {Boolean(report.missingKeywords?.length) && (
              <div className="cv-check-keywords-section">
                <h4 className="cv-check-keywords-title">
                  {t('cvCheck.results.missingKeywords', 'Từ khóa kỹ năng còn thiếu trong CV')}
                </h4>
                <div className="cv-check-chips-wrap">
                  {report.missingKeywords.map((kw) => (
                    <span key={kw} className="cv-check-chip">
                      {kw}
                    </span>
                  ))}
                </div>
              </div>
            )}
          </div>
        )}

        {activeTab === 'bullets' && (
          <div className="cv-check-tab-panel">
            <div className="cv-check-bullets-list">
              {report.rewrittenBullets?.length ? (
                report.rewrittenBullets.map((bullet) => (
                  <div key={bullet.original} className="cv-check-bullet-card">
                    <div className="cv-check-bullet-row cv-check-bullet-row--orig">
                      <strong>{t('cvCheck.bullets.original', '[Bản gốc]:')}</strong>
                      {bullet.original}
                    </div>
                    <div className="cv-check-bullet-row cv-check-bullet-row--improved">
                      <strong>{t('cvCheck.bullets.improved', '[Đề xuất STAR]:')}</strong>
                      {bullet.improved}
                    </div>
                  </div>
                ))
              ) : (
                <p>{t('cvCheck.bullets.empty', 'Không có gợi ý viết lại nào.')}</p>
              )}
            </div>
          </div>
        )}

        {activeTab === 'interview' && (
          <div className="cv-check-tab-panel">
            <div className="cv-check-questions-list">
              {report.interviewQuestions?.length ? (
                report.interviewQuestions.map((q, idx) => (
                  <div key={q} className="cv-check-question-card">
                    <span className="cv-check-question-num">{idx + 1}</span>
                    <span>{q}</span>
                  </div>
                ))
              ) : (
                <p>{t('cvCheck.interview.empty', 'Không có câu hỏi phỏng vấn nào.')}</p>
              )}
            </div>
          </div>
        )}

        {activeTab === 'jobs' && (
          <CvJobsTab
            report={report}
            jobRecs={jobRecs}
            isPending={jobsPending}
            isError={jobsError}
            activeLang={activeLang}
            onRetry={onRetryJobs}
            t={t}
          />
        )}
      </div>
    </div>
  );
}

export function CvCheckPage() {
  const { t, i18n } = useTranslation();
  const activeLang = (i18n.resolvedLanguage ?? i18n.language)?.toLowerCase().startsWith('en')
    ? 'en'
    : 'vi';
  const fileUploadId = useId();

  const [cvFile, setCvFile] = useState<File | null>(null);
  const [cvPdfUrl, setCvPdfUrl] = useState<string | null>(null);
  const [cvText, setCvText] = useState('');
  const [report, setReport] = useState<CvCheckReport | null>(null);
  const [reportLang, setReportLang] = useState<'en' | 'vi' | null>(null);
  const [jobRecs, setJobRecs] = useState<JobRecommendationsResponse | null>(null);
  const [activeTab, setActiveTab] = useState<ActiveTab>('evaluation');
  const [isDragOver, setIsDragOver] = useState(false);
  const [progressMode, setProgressMode] = useState<ProgressMode>('idle');
  const [progressStep, setProgressStep] = useState(0);
  const timersRef = useRef<number[]>([]);

  const checkMutation = useCvCheckMutation();
  const jobsMutation = useRecommendJobsMutation();

  const clearProgressTimers = () => {
    for (const id of timersRef.current) {
      window.clearTimeout(id);
    }
    timersRef.current = [];
  };

  useEffect(() => () => clearProgressTimers(), []);

  useEffect(() => {
    return () => {
      if (cvPdfUrl && typeof URL.revokeObjectURL === 'function') {
        URL.revokeObjectURL(cvPdfUrl);
      }
    };
  }, [cvPdfUrl]);

  const setPdfFile = (file: File | null) => {
    if (cvPdfUrl && typeof URL.revokeObjectURL === 'function') {
      URL.revokeObjectURL(cvPdfUrl);
    }
    setCvFile(file);
    if (!file) {
      setCvPdfUrl(null);
      return;
    }
    setCvPdfUrl(
      typeof URL.createObjectURL === 'function' ? URL.createObjectURL(file) : 'blob:preview-cv',
    );
  };

  const handleClear = () => {
    clearProgressTimers();
    setProgressMode('idle');
    setProgressStep(0);
    setPdfFile(null);
    setCvText('');
    setReport(null);
    setReportLang(null);
    setJobRecs(null);
    jobsMutation.reset();
  };

  const isPdfFile = (file: File) =>
    file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf');

  const handleFileUpload = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file && isPdfFile(file)) {
      setPdfFile(file);
      setCvText('');
    }
    e.target.value = '';
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file && isPdfFile(file)) {
      setPdfFile(file);
      setCvText('');
    }
  };

  const finishProgress = () => {
    clearProgressTimers();
    setProgressMode((current) => {
      if (current !== 'stepper') return 'idle';
      setProgressStep(4);
      timersRef.current.push(window.setTimeout(() => setProgressMode('idle'), 600));
      return 'stepper';
    });
  };

  const triggerJobSearch = async (
    profile: NonNullable<CvCheckReport['jobProfile']>,
    lang: 'en' | 'vi' = activeLang,
  ) => {
    try {
      const recs = await jobsMutation.mutateAsync({ ...profile, lang });
      setJobRecs(recs);
    } catch {
      // Handled via jobsMutation.isError
    } finally {
      finishProgress();
    }
  };

  const runAnalysis = async (lang: 'en' | 'vi') => {
    if (!cvFile && !cvText.trim()) return;
    clearProgressTimers();
    setJobRecs(null);
    jobsMutation.reset();
    setProgressMode('stepper');
    setProgressStep(0);

    timersRef.current.push(
      window.setTimeout(() => setProgressStep((s) => Math.max(s, 1)), 1200),
      window.setTimeout(() => setProgressStep((s) => Math.max(s, 2)), 2800),
      window.setTimeout(() => {
        setProgressMode((m) => (m === 'stepper' ? 'loading' : m));
      }, MAX_STEPPER_MS),
    );

    try {
      const result = await checkMutation.mutateAsync({ cvFile, cvText: cvText.trim(), lang });
      setReport(result);
      setReportLang(lang);
      setActiveTab('evaluation');
      if (result.jobProfile?.primaryRole) {
        setProgressStep(3);
        void triggerJobSearch(result.jobProfile, lang);
      } else {
        finishProgress();
      }
    } catch {
      clearProgressTimers();
      setProgressMode('idle');
    }
  };

  useEffect(() => {
    const needsRelocalize =
      Boolean(report && reportLang && reportLang !== activeLang) &&
      Boolean(cvFile || cvText.trim()) &&
      !checkMutation.isPending;
    if (needsRelocalize) {
      void runAnalysis(activeLang);
    }
  }, [activeLang]);

  const hasContent = Boolean(cvFile || cvText || report);

  return (
    <div className="cv-check-layout">
      <PageHeading
        title={t('cvCheck.pageTitle', 'CV Checker')}
        lede={t(
          'cvCheck.pageLede',
          'Tải lên file CV PDF để phân tích toàn diện: điểm chuẩn ATS, kỹ năng cốt lõi, tối ưu thành tích STAR và gợi ý câu hỏi phỏng vấn.',
        )}
        actions={
          hasContent ? (
            <div className="cv-check-panel__tools">
              <button
                type="button"
                className="cortex-btn cortex-btn--ghost"
                onClick={handleClear}
              >
                <Trash size={16} aria-hidden="true" />
                {t('cvCheck.actions.clearAll', 'Xóa tất cả')}
              </button>
            </div>
          ) : undefined
        }
      />

      {checkMutation.isError && <CvErrorBanner error={checkMutation.error} t={t} />}

      <div className="cv-check-workspace">
        <section
          className="cv-check-panel"
          aria-label={t('cvCheck.form.dropzoneAria', 'Khung tải lên file CV PDF')}
        >
          <input
            id={fileUploadId}
            type="file"
            accept="application/pdf,.pdf"
            style={{ display: 'none' }}
            onChange={handleFileUpload}
          />
          <div className="cv-check-panel__body">
            {cvFile && cvPdfUrl ? (
              <div className="cv-check-pdf-preview-box">
                <div className="cv-check-pdf-info-bar">
                  <div className="cv-check-pdf-info-left">
                    <FilePdf size={20} weight="fill" aria-hidden="true" />
                    <span title={cvFile.name}>{cvFile.name}</span>
                    <span className="cv-check-pdf-size">({formatSize(cvFile.size)})</span>
                  </div>
                  <div className="cv-check-panel__tools">
                    <label htmlFor={fileUploadId} className="cv-check-tool-btn">
                      <UploadSimple size={14} aria-hidden="true" />
                      {t('cvCheck.actions.changePdf', 'Đổi PDF khác')}
                    </label>
                    <button
                      type="button"
                      className="cv-check-tool-btn"
                      onClick={() => setPdfFile(null)}
                      title={t('cvCheck.actions.removePdf', 'Xóa file PDF')}
                    >
                      <Trash size={14} aria-hidden="true" />
                      {t('cvCheck.actions.removePdf', 'Xóa')}
                    </button>
                  </div>
                </div>
                <iframe
                  src={cvPdfUrl}
                  title={t('cvCheck.form.pdfPreviewTitle', 'Xem trước CV PDF')}
                  className="cv-check-pdf-frame"
                />
              </div>
            ) : (
              <button
                type="button"
                className={`cv-check-pdf-dropzone ${isDragOver ? 'is-dragover' : ''}`}
                onDragOver={(e) => {
                  e.preventDefault();
                  setIsDragOver(true);
                }}
                onDragLeave={(e) => {
                  e.preventDefault();
                  setIsDragOver(false);
                }}
                onDrop={handleDrop}
                onClick={() => document.getElementById(fileUploadId)?.click()}
                aria-label={t('cvCheck.form.dropzoneAria', 'Khung tải lên file CV PDF')}
              >
                <FilePdf
                  size={64}
                  weight="duotone"
                  className="cv-check-pdf-dropzone__icon"
                  aria-hidden="true"
                />
                <span className="cv-check-pdf-dropzone__title">
                  {t('cvCheck.form.dropzoneTitle', 'Kéo & thả file CV PDF vào đây')}
                </span>
                <span className="cv-check-pdf-dropzone__desc">
                  {t(
                    'cvCheck.form.dropzoneDesc',
                    'hoặc bấm để chọn từ máy tính · Chỉ hỗ trợ .pdf, tối đa 8MB',
                  )}
                </span>
                <span className="cv-check-pdf-dropzone__actions">
                  <span className="cortex-btn cortex-btn--primary">
                    <UploadSimple size={16} aria-hidden="true" />
                    {t('cvCheck.actions.browsePdf', 'Chọn file PDF')}
                  </span>
                </span>
              </button>
            )}
          </div>
        </section>

        <aside className="cv-check-side-actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--primary cv-check-submit-btn"
            disabled={(!cvFile && !cvText.trim()) || checkMutation.isPending}
            onClick={() => void runAnalysis(activeLang)}
          >
            {checkMutation.isPending ? (
              <>
                <ArrowsClockwise className="spinner" size={18} aria-hidden="true" />
                {t('cvCheck.actions.analyzing', 'Đang phân tích CV...')}
              </>
            ) : (
              <>
                <Sparkle size={18} weight="fill" aria-hidden="true" />
                {t('cvCheck.actions.analyze', 'Analyze CV')}
              </>
            )}
          </button>
        </aside>
      </div>

      <CvProgressIndicator
        progressMode={progressMode}
        progressStep={progressStep}
        isPending={checkMutation.isPending || jobsMutation.isPending}
        t={t}
      />

      {report && (
        <section
          className="cv-check-results"
          aria-label={t('cvCheck.results.title', 'Kết quả phân tích')}
        >
          <CvOverviewCard report={report} t={t} />
          <CvDetailedTabs
            report={report}
            activeTab={activeTab}
            onSelectTab={setActiveTab}
            jobRecs={jobRecs}
            jobsPending={jobsMutation.isPending}
            jobsError={jobsMutation.isError}
            activeLang={activeLang}
            onRetryJobs={(profile) => void triggerJobSearch(profile)}
            t={t}
          />
        </section>
      )}
    </div>
  );
}
