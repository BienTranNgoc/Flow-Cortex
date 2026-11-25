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
};

const ROUTES = {
  'POST /api/v1/cv-check': {
    status: 200,
    body: MOCK_REPORT,
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

  it('loads sample PDF and renders preview frame', async () => {
    stubApi(ROUTES);
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/check-cv', '/check-cv', <CvCheckPage />);
    await settled(queryClient);

    const loadSampleBtn = screen.getByRole('button', { name: /Tải CV PDF mẫu|Load Sample PDF/i });
    await user.click(loadSampleBtn);

    expect(await screen.findByTitle(/Xem trước CV PDF|PDF CV Preview/i)).toBeInTheDocument();
    expect(screen.getByText(/CV_Nguyen_Van_An_Backend_Java.pdf/i)).toBeInTheDocument();
  });

  it('submits form and displays analysis report score and dimensions', async () => {
    const calls = stubApi(ROUTES);
    const user = userEvent.setup();
    const { queryClient } = renderRoute('/check-cv', '/check-cv', <CvCheckPage />);
    await settled(queryClient);

    const loadSampleBtn = screen.getByRole('button', { name: /Tải CV PDF mẫu|Load Sample PDF/i });
    await user.click(loadSampleBtn);

    const submitBtn = screen.getByRole('button', { name: /Đánh giá CV ngay|Analyze CV/i });
    await user.click(submitBtn);

    await settled(queryClient);

    expect(calls.length).toBe(1);
    expect(calls[0]?.path).toBe('/api/v1/cv-check');
    expect(screen.getByText('85')).toBeInTheDocument();
    expect(screen.getByText(/Thành thạo Java và Spring Boot/i)).toBeInTheDocument();
  });
});
