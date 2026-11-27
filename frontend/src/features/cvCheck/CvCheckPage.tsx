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
import { type ChangeEvent, useEffect, useId, useRef, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import {
  type CvCheckReport,
  type JobRecommendationsResponse,
} from './cvCheckTypes';
import { useCvCheckMutation, useRecommendJobsMutation } from './useCvCheck';
import './CvCheckPage.css';

type ActiveTab = 'evaluation' | 'bullets' | 'interview' | 'jobs';
type ProgressMode = 'idle' | 'stepper' | 'loading';

const MAX_STEPPER_MS = 10_000;

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function CvCheckPage() {
  const { t, i18n } = useTranslation();
  const activeLang = i18n.language?.toLowerCase().startsWith('en') ? 'en' : 'vi';
  const fileUploadId = useId();

  const [cvFile, setCvFile] = useState<File | null>(null);
  const [cvPdfUrl, setCvPdfUrl] = useState<string | null>(null);
  const [cvText, setCvText] = useState('');
  const [report, setReport] = useState<CvCheckReport | null>(null);
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

  useEffect(() => {
    return () => clearProgressTimers();
  }, []);

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
    if (file) {
      if (typeof URL.createObjectURL === 'function') {
        setCvPdfUrl(URL.createObjectURL(file));
      } else {
        setCvPdfUrl('blob:preview-cv');
      }
    } else {
      setCvPdfUrl(null);
    }
  };

  const handleClear = () => {
    clearProgressTimers();
    setProgressMode('idle');
    setProgressStep(0);
    setPdfFile(null);
    setCvText('');
    setReport(null);
    setJobRecs(null);
    jobsMutation.reset();
  };

  const handleFileUpload = (e: ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (!file) return;
    if (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf')) {
      setPdfFile(file);
      setCvText('');
    }
    e.target.value = '';
  };

  const handleDragOver = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(true);
  };

  const handleDragLeave = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
  };

  const handleDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setIsDragOver(false);
    const file = e.dataTransfer.files[0];
    if (file && (file.type === 'application/pdf' || file.name.toLowerCase().endsWith('.pdf'))) {
      setPdfFile(file);
      setCvText('');
    }
  };

  const finishProgress = () => {
    clearProgressTimers();
    setProgressMode((current) => {
      if (current === 'stepper') {
        setProgressStep(4);
        timersRef.current.push(
          window.setTimeout(() => {
            setProgressMode('idle');
          }, 600),
        );
        return 'stepper';
      }
      return 'idle';
    });
  };

  const triggerJobSearch = async (profile: NonNullable<CvCheckReport['jobProfile']>) => {
    try {
      const recs = await jobsMutation.mutateAsync({ ...profile, lang: activeLang });
      setJobRecs(recs);
    } catch {
      // Handled via jobsMutation.isError
    } finally {
      finishProgress();
    }
  };

  const handleSubmit = async () => {
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
      const result = await checkMutation.mutateAsync({
        cvFile,
        cvText: cvText.trim(),
        lang: activeLang,
      });
      setReport(result);
      setActiveTab('evaluation');
      if (result.jobProfile?.primaryRole) {
        setProgressStep(3);
        void triggerJobSearch(result.jobProfile);
      } else {
        finishProgress();
      }
    } catch {
      clearProgressTimers();
      setProgressMode('idle');
      // Handled via checkMutation.isError and checkMutation.error
    }
  };

  const getScoreVerdict = (score: number) => {
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
  };

  const verdict = report ? getScoreVerdict(report.overallScore) : null;

  return (
    <div className="cv-check-layout">
      <PageHeading
        title={t('cvCheck.pageTitle', 'CV Checker')}
        lede={t(
          'cvCheck.pageLede',
          'Tải lên file CV PDF để phân tích toàn diện: điểm chuẩn ATS, kỹ năng cốt lõi, tối ưu thành tích STAR và gợi ý câu hỏi phỏng vấn.',
        )}
        actions={
          (cvFile || cvText || report) ? (
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

      {checkMutation.isError && (
        <div className="cv-check-error-banner" role="alert">
          <WarningCircle size={20} weight="fill" aria-hidden="true" />
          <span>
            {t(
              'cvCheck.error.general',
              'Đã xảy ra lỗi khi phân tích CV. Vui lòng kiểm tra lại file PDF hoặc thử lại sau ít phút.',
            )}
          </span>
        </div>
      )}

      {/* PDF Upload (Left) + Analyze Action (Right) */}
      <div className="cv-check-workspace">
        <section className="cv-check-panel" aria-labelledby="cv-panel-title">
          <header className="cv-check-panel__head">
            <h3 id="cv-panel-title" className="cv-check-panel__title">
              <FilePdf size={18} weight="fill" aria-hidden="true" style={{ color: 'var(--red)' }} />
              {t('cvCheck.form.cvTitle', 'Hồ sơ CV (Định dạng PDF)')}
            </h3>
            <div className="cv-check-panel__tools">
              <input
                id={fileUploadId}
                type="file"
                accept="application/pdf,.pdf"
                style={{ display: 'none' }}
                onChange={handleFileUpload}
              />
              {cvFile && (
                <label htmlFor={fileUploadId} className="cv-check-tool-btn">
                  <UploadSimple size={14} aria-hidden="true" />
                  {t('cvCheck.actions.changePdf', 'Đổi PDF khác')}
                </label>
              )}
            </div>
          </header>
          <div className="cv-check-panel__body">
            {cvFile && cvPdfUrl ? (
              <div className="cv-check-pdf-preview-box">
                <div className="cv-check-pdf-info-bar">
                  <div className="cv-check-pdf-info-left">
                    <FilePdf size={20} weight="fill" aria-hidden="true" />
                    <span title={cvFile.name}>{cvFile.name}</span>
                    <span className="cv-check-pdf-size">({formatSize(cvFile.size)})</span>
                  </div>
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
                <iframe
                  src={cvPdfUrl}
                  title={t('cvCheck.form.pdfPreviewTitle', 'Xem trước CV PDF')}
                  className="cv-check-pdf-frame"
                />
              </div>
            ) : (
              <div
                className={`cv-check-pdf-dropzone ${isDragOver ? 'is-dragover' : ''}`}
                onDragOver={handleDragOver}
                onDragLeave={handleDragLeave}
                onDrop={handleDrop}
                onClick={() => document.getElementById(fileUploadId)?.click()}
                role="button"
                tabIndex={0}
                onKeyDown={(e) => {
                  if (e.key === 'Enter' || e.key === ' ') {
                    document.getElementById(fileUploadId)?.click();
                  }
                }}
                aria-label={t('cvCheck.form.dropzoneAria', 'Khung tải lên file CV PDF')}
              >
                <FilePdf
                  size={64}
                  weight="duotone"
                  className="cv-check-pdf-dropzone__icon"
                  aria-hidden="true"
                />
                <h4 className="cv-check-pdf-dropzone__title">
                  {t('cvCheck.form.dropzoneTitle', 'Kéo & thả file CV PDF vào đây')}
                </h4>
                <p className="cv-check-pdf-dropzone__desc">
                  {t(
                    'cvCheck.form.dropzoneDesc',
                    'hoặc bấm để chọn file PDF từ máy tính của bạn (hỗ trợ .pdf tối đa 10MB)',
                  )}
                </p>
                <div
                  className="cv-check-pdf-dropzone__actions"
                  onClick={(e) => e.stopPropagation()}
                >
                  <label htmlFor={fileUploadId} className="cortex-btn cortex-btn--primary">
                    <UploadSimple size={16} aria-hidden="true" />
                    {t('cvCheck.actions.browsePdf', 'Chọn file PDF')}
                  </label>
                </div>
              </div>
            )}
          </div>
        </section>

        {/* Action Button on Right */}
        <aside className="cv-check-side-actions">
          <button
            type="button"
            className="cortex-btn cortex-btn--primary cv-check-submit-btn"
            disabled={(!cvFile && !cvText.trim()) || checkMutation.isPending}
            onClick={handleSubmit}
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

      {/* Analysis Progress (4-Step Bar up to 10s, then subtle animated loading) */}
      {progressMode === 'stepper' && (
        <div className="cv-check-stepper" role="status" aria-live="polite">
          {[
            t('cvCheck.progress.readPdf', 'Đọc file PDF'),
            t('cvCheck.progress.checkAts', 'Kiểm tra bố cục & ATS'),
            t('cvCheck.progress.scoreCv', 'Đánh giá & Chấm điểm'),
            t('cvCheck.progress.findJobs', 'Tìm việc phù hợp'),
          ].map((label, idx) => {
            const state = idx < progressStep ? 'done' : idx === progressStep ? 'active' : 'pending';
            return (
              <div key={idx} className={`cv-check-stepper__item is-${state}`}>
                <span className="cv-check-stepper__icon" aria-hidden="true">
                  {state === 'done' ? (
                    <CheckCircle size={18} weight="fill" />
                  ) : state === 'active' ? (
                    <ArrowsClockwise size={16} className="spinner" />
                  ) : (
                    <span className="cv-check-stepper__dot" />
                  )}
                </span>
                <span className="cv-check-stepper__label">{label}</span>
              </div>
            );
          })}
        </div>
      )}

      {progressMode === 'loading' && (checkMutation.isPending || jobsMutation.isPending) && (
        <div className="cv-check-loading-strip" role="status" aria-live="polite">
          <div className="cv-check-loading-strip__row">
            <ArrowsClockwise size={18} className="spinner" aria-hidden="true" />
            <span>{t('cvCheck.progress.finalizing', 'Đang hoàn tất phân tích...')}</span>
          </div>
          <div className="cv-check-loading-strip__track">
            <div className="cv-check-loading-strip__bar" />
          </div>
        </div>
      )}

      {/* Results Dashboard */}
      {report && (
        <section className="cv-check-results" aria-label={t('cvCheck.results.title', 'Kết quả phân tích')}>
          {/* Panel 1: Overview Score + Deterministic ATS Audit (Merged into 1 clean card) */}
          <div className="cv-check-overview-card">
            <div className="cv-check-overview-top">
              <div className={`cv-check-score-box ${verdict?.cls ?? ''}`}>
                <span className="cv-check-score-number">{report.overallScore}</span>
                <span className="cv-check-score-label">
                  {t('cvCheck.results.overallScore', 'Điểm chuẩn quốc tế')} / 100
                </span>
                <span className="cv-check-score-verdict">{verdict?.label}</span>
                <span className="cv-check-rubric-tag">
                  {report.rubric?.name ?? 'Google XYZ · Harvard OCS · SHRM ATS'}
                </span>
              </div>

              <div className="cv-check-dimension-list">
                <div className="cv-check-dim-row">
                  <div className="cv-check-dim-head">
                    <span>
                      {t('cvCheck.dimensions.experienceImpact', 'Tác động & Định lượng (Google XYZ)')}{' '}
                      <em className="cv-check-dim-weight">({report.rubric?.weights.experienceImpact ?? 30}%)</em>
                    </span>
                    <span>{report.dimensions.experienceImpact}%</span>
                  </div>
                  <div className="cv-check-dim-bar">
                    <div
                      className="cv-check-dim-fill"
                      style={{ width: `${Math.min(100, Math.max(0, report.dimensions.experienceImpact))}%` }}
                    />
                  </div>
                </div>

                <div className="cv-check-dim-row">
                  <div className="cv-check-dim-head">
                    <span>
                      {t('cvCheck.dimensions.hardSkills', 'Kỹ năng cốt lõi (ESCO / O*NET)')}{' '}
                      <em className="cv-check-dim-weight">({report.rubric?.weights.hardSkills ?? 25}%)</em>
                    </span>
                    <span>{report.dimensions.hardSkills}%</span>
                  </div>
                  <div className="cv-check-dim-bar">
                    <div
                      className="cv-check-dim-fill"
                      style={{ width: `${Math.min(100, Math.max(0, report.dimensions.hardSkills))}%` }}
                    />
                  </div>
                </div>

                <div className="cv-check-dim-row">
                  <div className="cv-check-dim-head">
                    <span>
                      {t('cvCheck.dimensions.atsCompliance', 'Chuẩn ATS & Cấu trúc (SHRM)')}{' '}
                      <em className="cv-check-dim-weight">({report.rubric?.weights.atsCompliance ?? 20}%)</em>
                    </span>
                    <span>{report.dimensions.atsCompliance}%</span>
                  </div>
                  <div className="cv-check-dim-bar">
                    <div
                      className="cv-check-dim-fill"
                      style={{ width: `${Math.min(100, Math.max(0, report.dimensions.atsCompliance))}%` }}
                    />
                  </div>
                </div>

                <div className="cv-check-dim-row">
                  <div className="cv-check-dim-head">
                    <span>
                      {t('cvCheck.dimensions.domainRelevance', 'Lộ trình & Độ rõ ràng (Harvard OCS)')}{' '}
                      <em className="cv-check-dim-weight">({report.rubric?.weights.domainRelevance ?? 15}%)</em>
                    </span>
                    <span>{report.dimensions.domainRelevance}%</span>
                  </div>
                  <div className="cv-check-dim-bar">
                    <div
                      className="cv-check-dim-fill"
                      style={{ width: `${Math.min(100, Math.max(0, report.dimensions.domainRelevance))}%` }}
                    />
                  </div>
                </div>

                <div className="cv-check-dim-row">
                  <div className="cv-check-dim-head">
                    <span>
                      {t('cvCheck.dimensions.softSkills', 'Kỹ năng mềm & Văn phong')}{' '}
                      <em className="cv-check-dim-weight">({report.rubric?.weights.softSkills ?? 10}%)</em>
                    </span>
                    <span>{report.dimensions.softSkills}%</span>
                  </div>
                  <div className="cv-check-dim-bar">
                    <div
                      className="cv-check-dim-fill"
                      style={{ width: `${Math.min(100, Math.max(0, report.dimensions.softSkills))}%` }}
                    />
                  </div>
                </div>
              </div>
            </div>

            {report.atsChecks && report.atsChecks.length > 0 && (
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
                  {report.atsChecks.map((check) => (
                    <li
                      key={check.id}
                      className={`cv-check-ats-row cv-check-ats-row--${check.status.toLowerCase()}`}
                    >
                      <span className="cv-check-ats-badge">
                        {check.status === 'PASS'
                          ? t('cvCheck.atsChecklist.pass', 'ĐẠT')
                          : check.status === 'WARN'
                            ? t('cvCheck.atsChecklist.warn', 'CẢNH BÁO')
                            : t('cvCheck.atsChecklist.fail', 'CHƯA ĐẠT')}
                      </span>
                      <span className="cv-check-ats-item__detail">{check.detail}</span>
                      <span className="cv-check-ats-standard">{check.standard}</span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
          </div>

          {/* Detailed Tabs */}
          <div className="cv-check-tabs-container">
            <nav className="cv-check-tabs-nav" aria-label="Kết quả chi tiết">
              <button
                type="button"
                className={`cv-check-tab-btn ${activeTab === 'evaluation' ? 'is-active' : ''}`}
                onClick={() => setActiveTab('evaluation')}
              >
                <CheckCircle size={16} aria-hidden="true" />
                {t('cvCheck.tabs.evaluation', 'Điểm mạnh & Điểm yếu')}
              </button>
              <button
                type="button"
                className={`cv-check-tab-btn ${activeTab === 'bullets' ? 'is-active' : ''}`}
                onClick={() => setActiveTab('bullets')}
              >
                <Sparkle size={16} aria-hidden="true" />
                {t('cvCheck.tabs.bullets', 'Tối ưu câu mô tả (STAR)')}
              </button>
              <button
                type="button"
                className={`cv-check-tab-btn ${activeTab === 'interview' ? 'is-active' : ''}`}
                onClick={() => setActiveTab('interview')}
              >
                <FileText size={16} aria-hidden="true" />
                {t('cvCheck.tabs.interview', 'Dự đoán câu hỏi phỏng vấn')}
              </button>
              <button
                type="button"
                className={`cv-check-tab-btn ${activeTab === 'jobs' ? 'is-active' : ''}`}
                onClick={() => setActiveTab('jobs')}
              >
                <Briefcase size={16} aria-hidden="true" />
                {t('cvCheck.tabs.jobs', 'Việc làm phù hợp')}
                {jobsMutation.isPending && (
                  <ArrowsClockwise className="spinner" size={14} aria-hidden="true" />
                )}
                {!jobsMutation.isPending && jobRecs && jobRecs.jobs.length > 0 && (
                  <span className="cv-check-tab-count">{jobRecs.jobs.length}</span>
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
                        {report.strengths.map((str, idx) => (
                          <li key={idx}>{str}</li>
                        ))}
                      </ul>
                    </div>

                    <div className="cv-check-eval-box cv-check-eval-box--weakness">
                      <h4 className="cv-check-eval-box__title">
                        <WarningCircle size={18} weight="fill" aria-hidden="true" />
                        {t('cvCheck.results.weaknesses', 'Điểm cần cải thiện')}
                      </h4>
                      <ul className="cv-check-eval-list">
                        {report.weaknesses.map((weak, idx) => (
                          <li key={idx}>{weak}</li>
                        ))}
                      </ul>
                    </div>
                  </div>

                  {report.missingKeywords && report.missingKeywords.length > 0 && (
                    <div className="cv-check-keywords-section">
                      <h4 className="cv-check-keywords-title">
                        {t('cvCheck.results.missingKeywords', 'Từ khóa kỹ năng còn thiếu trong CV')}
                      </h4>
                      <div className="cv-check-chips-wrap">
                        {report.missingKeywords.map((kw, idx) => (
                          <span key={idx} className="cv-check-chip">
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
                    {report.rewrittenBullets && report.rewrittenBullets.length > 0 ? (
                      report.rewrittenBullets.map((bullet, idx) => (
                        <div key={idx} className="cv-check-bullet-card">
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
                    {report.interviewQuestions && report.interviewQuestions.length > 0 ? (
                      report.interviewQuestions.map((q, idx) => (
                        <div key={idx} className="cv-check-question-card">
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
                          {new Date(jobRecs.lastSyncedAt).toLocaleDateString(activeLang === 'en' ? 'en-US' : 'vi-VN')}
                        </span>
                      )}
                      {jobRecs?.cached && (
                        <span className="cv-check-profile-pill cv-check-profile-pill--role">
                          {t('cvCheck.jobs.cachedBadge', 'Từ bộ nhớ đệm · Tự làm mới sau 7 ngày')}
                        </span>
                      )}
                    </div>
                  )}

                  {jobsMutation.isPending && (
                    <div className="cv-check-jobs-loading" role="status">
                      <ArrowsClockwise className="spinner" size={20} aria-hidden="true" />
                      <span>
                        {t(
                          'cvCheck.jobs.loading',
                          'Đang tìm tin tuyển dụng tại Việt Nam phù hợp với hồ sơ...',
                        )}
                      </span>
                    </div>
                  )}

                  {jobsMutation.isError && !jobsMutation.isPending && (
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
                          onClick={() => void triggerJobSearch(report.jobProfile!)}
                        >
                          {t('cvCheck.jobs.retry', 'Thử lại')}
                        </button>
                      )}
                    </div>
                  )}

                  {!jobsMutation.isPending && jobRecs && jobRecs.jobs.length > 0 && (
                    <div className="cv-check-jobs-grid">
                      {jobRecs.jobs.map((job, idx) => (
                        <article key={idx} className="cv-check-job-card">
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

                          {job.salary && (
                            <div className="cv-check-job-salary">{job.salary}</div>
                          )}

                          {job.summary && (
                            <p className="cv-check-job-card__summary">{job.summary}</p>
                          )}

                          {job.matchedSkills.length > 0 && (
                            <div className="cv-check-chips-wrap">
                              {job.matchedSkills.map((sk, sIdx) => (
                                <span key={sIdx} className="cv-check-job-skill">
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

                  {!jobsMutation.isPending && jobRecs && jobRecs.jobs.length === 0 && (
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

                  {jobRecs && jobRecs.searchLinks.length > 0 && (
                    <div className="cv-check-search-links">
                      <span className="cv-check-search-links__label">
                        {t('cvCheck.jobs.moreOnBoards', 'Tìm thêm trên các sàn tuyển dụng:')}
                      </span>
                      <div className="cv-check-search-links__list">
                        {jobRecs.searchLinks.map((link, idx) => (
                          <a
                            key={idx}
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
              )}
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
