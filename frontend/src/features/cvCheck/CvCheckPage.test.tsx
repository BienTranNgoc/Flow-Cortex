import { setToken } from '@flow-cortex/api-client';
import { screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { renderRoute, settled, stubApi } from '../../test/fakeApi';
import { CvCheckPage } from './CvCheckPage';
import type { CvCheckReport } from './cvCheckTypes';

const MOCK_REPORT: CvCheckReport = {
  overallScore: 85,
  dimensions: {
    hardSkills: 90,
    experienceImpact: 80,
    softSkills: 85,
    atsCompliance: 95,
    domainRelevance: 75,
  },
  strengths: ['Thành thạo Java và Spring Boot', 'Có kinh nghiệm microservices và Redis caching'],
  weaknesses: ['Chưa có kinh nghiệm với Apache Kafka'],
  missingKeywords: ['Kafka', 'Fraud Detection'],
  rewrittenBullets: [
    {
      original: 'Sử dụng Redis cache để lưu dữ liệu danh mục',
      improved: 'Thiết kế cơ chế caching đa tầng với Redis, giảm độ trễ API từ 450ms xuống 120ms (giảm 73%)',
    },
  ],
  interviewQuestions: ['Bạn đã giải quyết vấn đề cache stampede hoặc cache invalidation trong Redis như thế nào?'],
  jobProfile: {
    primaryRole: 'Java Backend Developer',
    seniority: 'Middle',
    topSkills: ['Java', 'Spring Boot', 'PostgreSQL', 'Redis'],
    location: 'Hà Nội',
  },
  rubric: {
    id: 'google-xyz-harvard-shrm-v1',
    name: 'Google XYZ · Harvard OCS · SHRM ATS',
    tier: 'TIER_2',
    weights: {
      experienceImpact: 30,
      hardSkills: 25,
      atsCompliance: 20,
      domainRelevance: 15,
      softSkills: 10,
    },
    capsApplied: [],
  },
  atsChecks: [
    { id: 'page_count', status: 'PASS', detail: '1 trang (đạt chuẩn 1–2 trang)', standard: 'Harvard OCS' },
    { id: 'quantified_metrics', status: 'PASS', detail: 'Phát hiện 5 chỉ số định lượng (tốt)', standard: 'Google XYZ' },
  ],
};

const MOCK_JOBS = {
  jobs: [
    {
      title: 'Middle/Senior Java Backend Developer',
      company: 'FinTech Vietnam',
      location: 'Hà Nội',
      salary: '$1,800 - $2,800',
      workMode: 'Hybrid',
      matchScore: 91,
      matchedSkills: ['Java', 'Spring Boot', 'Redis'],
      summary: 'Phù hợp kinh nghiệm Spring Boot và hệ thống thanh toán.',
      source: 'ITviec',
      applyUrl: 'https://itviec.com/it-jobs/java-backend',
    },
  ],
  searchLinks: [
    { label: 'ITviec', url: 'https://itviec.com/it-jobs/java-backend-developer' },
    { label: 'TopCV', url: 'https://www.topcv.vn/tim-viec-lam-java-backend-developer' },
  ],
};

const ROUTES = {
  'POST /api/v1/cv-check': {
    status: 200,
    body: MOCK_REPORT,
  },
  'POST /api/v1/cv-check/jobs': {
    status: 200,
    body: MOCK_JOBS,
  },
};

describe('CvCheckPage', () => {
  beforeEach(() => {
    setToken('test-token', true);
    if (typeof URL.createObjectURL === 'undefined') {
      URL.createObjectURL = vi.fn(() => 'blob:mock-pdf-url');
    }
    if (typeof URL.revokeObjectURL === 'undefined') {
      URL.revokeObjectURL = vi.fn();
    }
  });

  afterEach(() => {
    setToken(null);
    vi.unstubAllGlobals();
  });

  it('renders heading and PDF dropzone', async () => {
    stubApi(ROUTES);
    const { queryClient } = renderRoute('/check-cv', '/check-cv', <CvCheckPage />);
    await settled(queryClient);

    expect(screen.getByRole('heading', { level: 1 })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /Khung tải lên file CV PDF|PDF CV upload frame/i })).toBeInTheDocument();
  });

  it('uploads PDF and renders preview frame', async () => {
    stubApi(ROUTES);
    const user = userEvent.setup();
    const { container, queryClient } = renderRoute('/check-cv', '/check-cv', <CvCheckPage />);
    await settled(queryClient);

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const pdfFile = new File(['%PDF-1.4 sample'], 'CV_Nguyen_Van_An_Backend_Java.pdf', {
      type: 'application/pdf',
    });
    await user.upload(input, pdfFile);

    expect(await screen.findByTitle(/Xem trước CV PDF|PDF CV Preview/i)).toBeInTheDocument();
    expect(screen.getByText(/CV_Nguyen_Van_An_Backend_Java.pdf/i)).toBeInTheDocument();
  });

  it('submits form and displays analysis report score and dimensions', async () => {
    const calls = stubApi(ROUTES);
    const user = userEvent.setup();
    const { container, queryClient } = renderRoute('/check-cv', '/check-cv', <CvCheckPage />);
    await settled(queryClient);

    const input = container.querySelector('input[type="file"]') as HTMLInputElement;
    const pdfFile = new File(['%PDF-1.4 sample'], 'CV_Nguyen_Van_An_Backend_Java.pdf', {
      type: 'application/pdf',
    });
    await user.upload(input, pdfFile);

    const submitBtn = screen.getByRole('button', { name: /Analyze CV|Đánh giá CV/i });
    await user.click(submitBtn);

    await settled(queryClient);

    expect(calls.length).toBe(2);
    expect(calls[0]?.path).toBe('/api/v1/cv-check');
    expect(calls[1]?.path).toBe('/api/v1/cv-check/jobs');
    expect(screen.getByText('85')).toBeInTheDocument();
    expect(screen.getByText('Google XYZ · Harvard OCS · SHRM ATS')).toBeInTheDocument();
    expect(screen.getByText('1 trang (đạt chuẩn 1–2 trang)')).toBeInTheDocument();
    expect(screen.getByText(/Thành thạo Java và Spring Boot/i)).toBeInTheDocument();

    const jobsTab = screen.getByRole('button', { name: /Việc làm phù hợp|Matching Jobs/i });
    await user.click(jobsTab);
    expect(await screen.findByText('Middle/Senior Java Backend Developer')).toBeInTheDocument();
    expect(screen.getByText('FinTech Vietnam')).toBeInTheDocument();
  });
});
