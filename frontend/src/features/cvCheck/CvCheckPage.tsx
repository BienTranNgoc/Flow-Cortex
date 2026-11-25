import {
  ArrowsClockwise,
  Briefcase,
  CheckCircle,
  FilePdf,
  FileText,
  Sparkle,
  Trash,
  UploadSimple,
  WarningCircle,
} from '@phosphor-icons/react';
import { type ChangeEvent, useEffect, useId, useState } from 'react';
import { useTranslation } from 'react-i18next';
import { PageHeading } from '../../app/PageHeading';
import {
  createSamplePdfBlob,
  SAMPLE_CV,
  SAMPLE_JD,
  type CvCheckReport,
} from './cvCheckTypes';
import { useCvCheckMutation } from './useCvCheck';
import './CvCheckPage.css';

type ActiveTab = 'evaluation' | 'bullets' | 'interview';

function formatSize(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

export function CvCheckPage() {
  const { t } = useTranslation();
  const jdInputId = useId();
  const fileUploadId = useId();

  const [cvFile, setCvFile] = useState<File | null>(null);
  const [cvPdfUrl, setCvPdfUrl] = useState<string | null>(null);
  const [cvText, setCvText] = useState('');
  const [jobDescription, setJobDescription] = useState('');
  const [report, setReport] = useState<CvCheckReport | null>(null);
  const [activeTab, setActiveTab] = useState<ActiveTab>('evaluation');
  const [isDragOver, setIsDragOver] = useState(false);

  const checkMutation = useCvCheckMutation();

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

  const handleLoadSample = () => {
    const blob = createSamplePdfBlob();
    const sampleFile = new File([blob], 'CV_Nguyen_Van_An_Backend_Java.pdf', {
      type: 'application/pdf',
    });
    setPdfFile(sampleFile);
    setCvText(SAMPLE_CV);
    setJobDescription(SAMPLE_JD);
  };

  const handleClear = () => {
    setPdfFile(null);
    setCvText('');
    setJobDescription('');
    setReport(null);
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

  const handleSubmit = async () => {
    if ((!cvFile && !cvText.trim()) || !jobDescription.trim()) return;

    try {
      const result = await checkMutation.mutateAsync({
        cvFile,
        cvText: cvText.trim(),
        jobDescription: jobDescription.trim(),
      });
      setReport(result);
      setActiveTab('evaluation');
    } catch {
      // Handled via checkMutation.isError and checkMutation.error
    }
  };

  const getScoreVerdict = (score: number) => {
    if (score >= 80) {
      return {
        cls: 'score--excellent',
        label: t('cvCheck.verdict.excellent', 'Rất phù hợp (Strong Fit)'),
      };
    }
    if (score >= 65) {
      return {
        cls: 'score--good',
        label: t('cvCheck.verdict.good', 'Tiềm năng tốt (Good Fit)'),
      };
    }
    if (score >= 50) {
      return {
        cls: 'score--moderate',
        label: t('cvCheck.verdict.moderate', 'Cần bổ sung (Moderate Fit)'),
      };
    }
    return {
      cls: 'score--poor',
      label: t('cvCheck.verdict.poor', 'Chưa phù hợp (Weak Fit)'),
    };
  };

  const verdict = report ? getScoreVerdict(report.overallScore) : null;

  return (
    <div className="cv-check-layout">
      <PageHeading
        title={t('cvCheck.pageTitle', 'Kiểm tra CV với AI (Gemini)')}
        lede={t(
          'cvCheck.pageLede',
          'Đánh giá độ khớp giữa hồ sơ ứng viên và tin tuyển dụng (JD). Phân tích điểm mạnh, điểm yếu, chuẩn hóa câu mô tả STAR và gợi ý câu hỏi phỏng vấn.',
        )}
        actions={
          <div className="cv-check-panel__tools">
            <button
              type="button"
              className="cortex-btn cortex-btn--ghost"
              onClick={handleLoadSample}
            >
              <Sparkle size={16} aria-hidden="true" />
              {t('cvCheck.actions.loadSample', 'Tải CV PDF mẫu')}
            </button>
            {(cvFile || cvText || jobDescription || report) && (
              <button
                type="button"
                className="cortex-btn cortex-btn--ghost"
                onClick={handleClear}
              >
                <Trash size={16} aria-hidden="true" />
                {t('cvCheck.actions.clearAll', 'Xóa tất cả')}
              </button>
            )}
          </div>
        }
      />

      <div className="cv-check-intro">
        <div className="cv-check-intro__info">
          <h2 className="cv-check-intro__title">
            {t('cvCheck.intro.title', 'Khung đối soát hồ sơ CV PDF')}
          </h2>
          <p className="cv-check-intro__desc">
            {t(
              'cvCheck.intro.desc',
              'Tải trực tiếp file CV PDF để xem trước toàn diện và trích xuất nội dung tự động bằng Apache PDFBox + Google Gemini 3.5 Flash.',
            )}
          </p>
        </div>
        <span className="cv-check-intro__badge">
          <Sparkle size={14} weight="fill" aria-hidden="true" />
          Gemini 3.5 Flash
        </span>
      </div>

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

      {/* 2-Column Form */}
      <div className="cv-check-form">
        {/* PDF Input Box */}
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
              <label htmlFor={fileUploadId} className="cv-check-tool-btn">
                <UploadSimple size={14} aria-hidden="true" />
                {cvFile
                  ? t('cvCheck.actions.changePdf', 'Đổi PDF khác')
                  : t('cvCheck.actions.uploadPdf', 'Tải file PDF')}
              </label>
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
                  <button
                    type="button"
                    className="cortex-btn cortex-btn--ghost"
                    onClick={handleLoadSample}
                  >
                    <Sparkle size={16} aria-hidden="true" />
                    {t('cvCheck.actions.useSamplePdf', 'Dùng CV PDF mẫu')}
                  </button>
                </div>
              </div>
            )}
          </div>
        </section>

        {/* JD Input Panel */}
        <section className="cv-check-panel" aria-labelledby="jd-panel-title">
          <header className="cv-check-panel__head">
            <h3 id="jd-panel-title" className="cv-check-panel__title">
              <Briefcase size={18} aria-hidden="true" />
              {t('cvCheck.form.jdTitle', 'Bản mô tả công việc (JD)')}
            </h3>
          </header>
          <div className="cv-check-panel__body">
            <textarea
              id={jdInputId}
              aria-labelledby="jd-panel-title"
              className="cv-check-textarea"
              placeholder={t(
                'cvCheck.form.jdPlaceholder',
                'Dán bản mô tả công việc (JD) mục tiêu vào đây (yêu cầu kỹ thuật, trách nhiệm công việc, quyền lợi)...',
              )}
              value={jobDescription}
              onChange={(e) => setJobDescription(e.target.value)}
              maxLength={10000}
            />
            <div className="cv-check-panel__foot">
              <span>{t('cvCheck.form.maxCharsJd', 'Tối đa 10.000 ký tự')}</span>
              <span>{jobDescription.length} / 10.000</span>
            </div>
          </div>
        </section>
      </div>

      {/* Action Button */}
      <div className="cv-check-actions">
        <button
          type="button"
          className="cortex-btn cortex-btn--primary cv-check-submit-btn"
          disabled={(!cvFile && !cvText.trim()) || !jobDescription.trim() || checkMutation.isPending}
          onClick={handleSubmit}
        >
          {checkMutation.isPending ? (
            <>
              <ArrowsClockwise className="spinner" size={18} aria-hidden="true" />
              {t('cvCheck.actions.analyzing', 'Đang trích xuất & chấm điểm với Gemini...')}
            </>
          ) : (
            <>
              <Sparkle size={18} weight="fill" aria-hidden="true" />
              {t('cvCheck.actions.analyze', 'Đánh giá CV ngay')}
            </>
          )}
        </button>
      </div>

      {/* Results Dashboard */}
      {report && (
        <section className="cv-check-results" aria-label={t('cvCheck.results.title', 'Kết quả phân tích')}>
          {/* Overview Score Card */}
          <div className="cv-check-overview-card">
            <div className={`cv-check-score-box ${verdict?.cls ?? ''}`}>
              <span className="cv-check-score-number">{report.overallScore}</span>
              <span className="cv-check-score-label">
                {t('cvCheck.results.overallScore', 'Điểm phù hợp')} / 100
              </span>
              <span className="cv-check-score-verdict">{verdict?.label}</span>
            </div>

            <div className="cv-check-dimension-list">
              <div className="cv-check-dim-row">
                <div className="cv-check-dim-head">
                  <span>{t('cvCheck.dimensions.hardSkills', 'Kỹ năng cốt lõi (Hard Skills)')}</span>
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
                  <span>{t('cvCheck.dimensions.experienceImpact', 'Tác động & Thành tích (STAR)')}</span>
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
                  <span>{t('cvCheck.dimensions.softSkills', 'Kỹ năng mềm & Văn hóa')}</span>
                  <span>{report.dimensions.softSkills}%</span>
                </div>
                <div className="cv-check-dim-bar">
                  <div
                    className="cv-check-dim-fill"
                    style={{ width: `${Math.min(100, Math.max(0, report.dimensions.softSkills))}%` }}
                  />
                </div>
              </div>

              <div className="cv-check-dim-row">
                <div className="cv-check-dim-head">
                  <span>{t('cvCheck.dimensions.atsCompliance', 'Chuẩn ATS & Từ khóa')}</span>
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
                  <span>{t('cvCheck.dimensions.domainRelevance', 'Độ phù hợp nghiệp vụ')}</span>
                  <span>{report.dimensions.domainRelevance}%</span>
                </div>
                <div className="cv-check-dim-bar">
                  <div
                    className="cv-check-dim-fill"
                    style={{ width: `${Math.min(100, Math.max(0, report.dimensions.domainRelevance))}%` }}
                  />
                </div>
              </div>
            </div>
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
            </div>
          </div>
        </section>
      )}
    </div>
  );
}
