import type { WorkflowResponse } from '@flow-cortex/api-client';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { MemoryRouter, Route, Routes } from 'react-router-dom';
import i18n from '../../lib/i18n';
import { WorkflowCard } from './WorkflowCard';

const PRODUCT_VIDEO: WorkflowResponse = {
  id: 'wf-pv',
  name: 'Product Video',
  visibility: 'public',
  available: true,
  steps: [
    { id: 's1', stepOrder: 1, name: 'script', type: 'script', mode: 'inline' },
    { id: 's2', stepOrder: 2, name: 'approval', type: 'APPROVAL_CHECKPOINT', mode: 'inline' },
    { id: 's3', stepOrder: 3, type: 'render', mode: 'remote' },
  ],
};

describe('WorkflowCard in English', () => {
  it('says "step" for one step and "steps" for several', () => {
    const single = { ...PRODUCT_VIDEO, steps: (PRODUCT_VIDEO.steps ?? []).slice(0, 1) };
    const { unmount } = render(
      <MemoryRouter>
        <WorkflowCard workflow={single} />
      </MemoryRouter>,
    );
    expect(screen.getByText('1 step')).toBeInTheDocument();
    unmount();

    render(
      <MemoryRouter>
        <WorkflowCard workflow={PRODUCT_VIDEO} />
      </MemoryRouter>,
    );
    expect(screen.getByText('3 steps')).toBeInTheDocument();
  });
});

describe('WorkflowCard in Vietnamese', () => {
  beforeEach(async () => {
    await i18n.changeLanguage('vi');
  });
  afterEach(async () => {
    await i18n.changeLanguage('en');
  });

  it('renders the status, description, ETA, step count and fallback step name from vi keys', () => {
    render(
      <MemoryRouter>
        <WorkflowCard workflow={PRODUCT_VIDEO} />
      </MemoryRouter>,
    );

    expect(screen.getByRole('link', { name: 'Mở Product Video' })).toHaveAttribute(
      'href',
      '/workflows/wf-pv',
    );
    expect(screen.getByText('Được chia sẻ với mọi thành viên')).toBeInTheDocument();
    expect(screen.getByText('Cần duyệt')).toBeInTheDocument();
    expect(
      screen.getByText('Tạo video sản phẩm trọn gói: kịch bản, giọng đọc và bản kết xuất cuối.'),
    ).toBeInTheDocument();
    expect(screen.getByText('~3 phút')).toBeInTheDocument();
    expect(screen.getByText('3 bước')).toBeInTheDocument();
    expect(screen.getByText('Bước 3')).toBeInTheDocument();
    expect(screen.getByText('Khởi chạy')).toBeInTheDocument();
  });
});

describe('WorkflowCard anatomy', () => {
  function follows(a: Node, b: Node) {
    return Boolean(a.compareDocumentPosition(b) & Node.DOCUMENT_POSITION_FOLLOWING);
  }

  it('puts the media tile first, then title, description and chips', () => {
    const { container } = render(
      <MemoryRouter>
        <WorkflowCard workflow={PRODUCT_VIDEO} />
      </MemoryRouter>,
    );

    const link = screen.getByRole('link', { name: 'Open Product Video' });
    const media = link.firstElementChild;
    expect(media).toHaveClass('workflow-card__media');
    expect(media).toContainElement(screen.getByText('script'));
    const title = screen.getByRole('heading', { name: 'Product Video' });
    const description = screen.getByText(/Generate a product video end-to-end/);
    const chips = container.querySelector('.workflow-card__chips');
    expect(chips).not.toBeNull();
    expect(follows(media as Node, title)).toBe(true);
    expect(follows(title, description)).toBe(true);
    expect(follows(description, chips as Node)).toBe(true);
    expect(chips).toHaveTextContent('3 steps');
  });

  it('opens the workflow page when the card is chosen', async () => {
    const user = userEvent.setup();
    render(
      <MemoryRouter initialEntries={['/']}>
        <Routes>
          <Route path="/" element={<WorkflowCard workflow={PRODUCT_VIDEO} />} />
          <Route path="/workflows/:id" element={<p>Detail wf-pv</p>} />
        </Routes>
      </MemoryRouter>,
    );

    await user.click(screen.getByRole('link', { name: 'Open Product Video' }));

    expect(screen.getByText('Detail wf-pv')).toBeInTheDocument();
  });
});
